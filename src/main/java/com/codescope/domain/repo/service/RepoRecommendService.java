package com.codescope.domain.repo.service;

import com.codescope.client.llm.EmbeddingService;
import com.codescope.client.llm.LlmClient;
import com.codescope.domain.repo.dto.RecommendedRepoDto;
import com.codescope.domain.repo.dto.RepoRecommendResponse;
import com.codescope.domain.repo.entity.RepoEmbedding;
import com.codescope.domain.repo.repository.RepoEmbeddingJpaRepository;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RAG(Retrieval-Augmented Generation) 기반 레포 추천.
 *
 * 흐름: 사용자 스택 텍스트 → 질의 임베딩(search_query prefix) → pgvector
 * 유사도 검색(status=EMBEDDED만) → 프롬프트 조립("목록 안에서만" 제약
 * 명시) → LlmClient.generate() → 응답 DTO.
 *
 * 왜 RAG인가(Day 25 설계 원칙 재확인): 컨텍스트 없이 LLM에 "추천해줘"만
 * 던지면 실제로 존재하지 않는 레포 이름을 지어내는 환각이 발생할 수
 * 있다. pgvector 검색 결과를 프롬프트에 직접 주입하고 "이 목록 안에서만"
 * 이라는 제약을 명시하면, LLM이 그 목록을 벗어난 답을 내놓을 가능성을
 * 구조적으로 줄인다(완전히 막는다고 보장할 수는 없어 실제 검증이
 * 필요 — RepoRecommendServiceIntegrationTest 참고).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RepoRecommendService {

    // 유사도 검색 후보를 몇 개까지 프롬프트에 넣을지. 너무 많으면 프롬프트가
    // 길어져 로컬 LLM(context_length 제약, 응답 시간)에 부담을 주고,
    // 너무 적으면 추천 다양성이 떨어진다. CLAUDE.md API 명세의 기본값을 따름.
    private static final int CANDIDATE_LIMIT = 10;

    private final EmbeddingService embeddingService;
    private final RepoEmbeddingJpaRepository repoEmbeddingJpaRepository;
    private final LlmClient llmClient;

    public RepoRecommendResponse recommend(String stack) {
        float[] queryVector = embeddingService.embedQuery(stack);
        String queryVectorLiteral = new PGvector(queryVector).toString();

        List<RepoEmbedding> nearest = repoEmbeddingJpaRepository
                .findNearestEmbeddedByEmbedding(queryVectorLiteral, CANDIDATE_LIMIT);

        List<RecommendedRepoDto> candidates = nearest.stream()
                .map(re -> RecommendedRepoDto.from(re.getRepository()))
                .toList();

        if (candidates.isEmpty()) {
            log.warn("추천 후보 없음(EMBEDDED 상태 레포가 없거나 검색 결과 0건): stack={}", stack);
            return new RepoRecommendResponse(stack, "현재 추천할 수 있는 레포가 없습니다.", candidates);
        }

        String prompt = buildPrompt(stack, candidates);
        String recommendation = llmClient.generate(prompt);

        return new RepoRecommendResponse(stack, recommendation, candidates);
    }

    // 왜 "목록 안에서만" 제약을 두 번(머리말+맺음말) 반복하는가: 로컬
    // 소형 모델(llama3.2:3b)은 지시를 한 번만 주면 프롬프트 뒷부분(후보
    // 목록)에 밀려 앞부분 지시를 놓칠 수 있다는 우려가 있어, 목록 앞뒤로
    // 감싸 지시를 강조했다. 실제 효과는 실측 검증(6번 단계) 대상.
    //
    // 왜 "반드시 한국어로만 답하라"를 명시하는가(2026-08-16 실측 계기):
    //   이전에는 프롬프트 자체가 한국어로 쓰여있으니 모델도 당연히
    //   한국어로만 답할 것이라 암묵적으로 기대했으나, 실제로는 응답에
    //   일본어/태국어/베트남어/중국어 단어가 섞이는 것을 실측으로
    //   확인했다(docs/troubleshooting.md Day 26+27 참고). "목록 안에서만"
    //   제약과 동일하게 머리말+맺음말 두 곳에 명시해 지시가 후보
    //   목록에 밀려 잊히지 않도록 강조한다. 다만 이 프롬프트 강화만으로
    //   3B급 모델의 다국어 생성 열화가 완전히 사라진다고 보장할 수는
    //   없다는 점은 감안 — 근본 해결이 아니라 비용이 거의 없는 개선
    //   시도(코드 몇 줄)일 뿐이다.
    private String buildPrompt(String stack, List<RecommendedRepoDto> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 오픈소스 프로젝트 추천 도우미입니다.\n");
        sb.append("반드시 한국어로만 답하세요. 다른 언어(영어, 일본어, 중국어 등) 단어를 섞지 마세요.\n");
        sb.append("아래 [후보 레포 목록]에 있는 레포만 근거로 답변하세요. ");
        sb.append("목록에 없는 레포 이름은 절대 언급하지 마세요.\n\n");
        sb.append("[사용자 기술 스택]\n").append(stack).append("\n\n");
        sb.append("[후보 레포 목록]\n");

        for (int i = 0; i < candidates.size(); i++) {
            RecommendedRepoDto repo = candidates.get(i);
            sb.append(i + 1).append(". ").append(repo.fullName());
            if (repo.description() != null && !repo.description().isBlank()) {
                sb.append(" - ").append(repo.description());
            }
            sb.append(" (언어: ").append(repo.language() != null ? repo.language() : "미상");
            sb.append(", ★").append(repo.starCount()).append(")\n");
        }

        sb.append("\n위 목록 중에서 사용자 기술 스택과 가장 잘 맞는 레포를 1~3개 골라 ");
        sb.append("레포 이름과 함께 이유를 설명하세요. 위 목록에 없는 레포는 절대 만들어내지 마세요. ");
        sb.append("답변은 반드시 한국어로만 작성하세요.");

        return sb.toString();
    }
}
