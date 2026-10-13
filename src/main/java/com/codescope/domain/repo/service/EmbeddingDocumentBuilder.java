package com.codescope.domain.repo.service;

import com.codescope.domain.repo.entity.GithubRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 레포 1개를 대표하는 임베딩 입력 텍스트를 만든다:
 * "레포 이름 + 설명 + 언어"를 앞에 두고, 마크다운 잡음을 걷어낸 README "앞부분"만 붙인다.
 *
 * 왜 README 전체가 아니라 앞부분인가(2026-10-13 실측):
 *   - 비용: 기존에는 README 전체를 500자 청크로 나눠 청크마다 임베딩 호출 후
 *     평균냈다. 청크 수는 평균 82개, 중앙값 20개, 최대 661개(awesome-selfhosted)였다.
 *     레포 하나가 Ollama 호출 수백 번을 점유하는 동안, 사용자 추천 요청의 질의
 *     임베딩도 같은 세마포어 뒤에서 기다린다.
 *   - 품질: 링크 목록 수백 청크를 평균 내면 벡터가 특정 의미 없이 흐려져
 *     "무엇과도 조금씩 가까운" 허브가 된다. 기준선 평가(scripts/embedding-eval.py)에서
 *     목록형 README 레포들이 무관한 질의 4개의 상위 3위에 끼어들었다.
 *   - "이 레포가 무엇인가"는 대부분 README 앞부분(소개·특징)에 있다.
 *
 * 왜 잡음을 먼저 걷어내는가: README 맨 앞은 배지·로고 이미지·HTML 정렬 태그가
 * 차지하는 경우가 많다. 그대로 자르면 앞부분 예산이 URL과 태그로 소진된다.
 *
 * 왜 이름·설명·언어를 맨 앞에 두는가: GitHub 설명은 작성자가 직접 쓴 한 줄 요약이라
 * 가장 의미가 뚜렷하고, README가 거의 비어 있는 레포도 이것만으로 검색된다.
 */
@Component
public class EmbeddingDocumentBuilder {

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern CODE_FENCE = Pattern.compile("(```|~~~).*?\\1", Pattern.DOTALL);
    // 배지·로고 이미지. [![배지](이미지)](링크) 형태는 이미지가 먼저 지워져 [](링크)가 되고,
    // 아래 LINK 규칙에서 빈 텍스트로 사라진다.
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile("(?m)^\\s*\\[[^\\]]+]:\\s*\\S+.*$");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    // 제목(#)·강조(*,_)·인라인 코드(`)·인용(>)·표(|) 기호. 의미 없는 토큰만 차지한다.
    private static final Pattern MARKDOWN_SYMBOLS = Pattern.compile("[#*_`>|]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final int maxChars;

    public EmbeddingDocumentBuilder(@Value("${codescope.embedding.document-max-chars}") int maxChars) {
        this.maxChars = maxChars;
    }

    public String build(GithubRepository repository, String readme) {
        StringBuilder header = new StringBuilder(repository.getFullName());
        if (repository.getDescription() != null && !repository.getDescription().isBlank()) {
            header.append('\n').append(repository.getDescription().strip());
        }
        if (repository.getLanguage() != null && !repository.getLanguage().isBlank()) {
            header.append("\nLanguage: ").append(repository.getLanguage());
        }

        String body = clean(readme == null ? "" : readme);
        String document = body.isEmpty() ? header.toString() : header + "\n\n" + body;
        return truncateAtWord(document, maxChars);
    }

    String clean(String markdown) {
        String text = HTML_COMMENT.matcher(markdown).replaceAll(" ");
        text = CODE_FENCE.matcher(text).replaceAll(" ");
        text = IMAGE.matcher(text).replaceAll(" ");
        text = LINK.matcher(text).replaceAll("$1");
        text = REFERENCE_DEFINITION.matcher(text).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = URL.matcher(text).replaceAll(" ");
        text = MARKDOWN_SYMBOLS.matcher(text).replaceAll(" ");
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    // 단어 중간에서 끊기면 마지막 토큰이 깨진 조각이 되므로 직전 공백에서 자른다.
    private static String truncateAtWord(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf(' ', maxChars);
        return text.substring(0, cut > 0 ? cut : maxChars);
    }
}
