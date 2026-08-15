package com.codescope.domain.repo.service;

import com.codescope.client.llm.LlmClient;
import com.codescope.domain.repo.dto.TrendAnalysisResponse;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.TrendScore;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.repository.TrendScoreJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

/**
 * "왜 이 레포가 뜨는가"를 LLM으로 분석한다.
 *
 * 캐싱: Day 12에서 확정한 캐싱 3조건(읽기 빈도 높음 + 갱신 시점 예측
 * 가능 + 약간의 지연 허용)을 그대로 적용 — 트렌드 분석은 조건을 충족하므로
 * TTL 1시간 캐싱 대상. "인기 레포만" 조건부 캐싱 대신 TTL 일괄 적용
 * 원칙도 동일하게 재사용(RedisConfig의 defaultCacheConfig가 모든 캐시에
 * 동일 TTL 적용).
 *
 * 왜 캐시와 별개로 TrendScore에도 영구 저장하는가: Redis 캐시는 TTL
 * 만료 시 사라지는 휘발성 저장소다. 분석 결과를 나중에(캐시 만료 후에도)
 * 다시 조회하거나 다른 기능(예: 트렌드 랭킹 상세 화면)에서 재사용하려면
 * DB에도 남겨야 한다. 캐시=빠른 응답, DB=영구 기록으로 역할이 다르다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class TrendAnalysisService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final GithubRepositoryJpaRepository githubRepositoryJpaRepository;
    private final TrendScoreJpaRepository trendScoreJpaRepository;
    private final LlmClient llmClient;

    // 왜 캐시 미스 때마다 LLM을 다시 호출하는가(TrendScore에 이미
    // analysisText가 있어도 재사용하지 않는 이유): Day 12 원칙대로
    // "TTL 일괄 적용"만으로 갱신 주기를 통제하는 단순한 구조를 유지한다.
    // TTL(1시간)이 지나야 캐시가 비므로, 그 시점에 최신 star/fork 수
    // 기준으로 분석을 다시 생성하는 것이 "왜 지금 뜨는가"라는 질문의
    // 취지에도 더 맞는다(오래된 분석을 그대로 재사용하면 통계가 낡음).
    @Cacheable(value = "trendAnalysis", key = "#repoId")
    public TrendAnalysisResponse analyze(Long repoId) {
        GithubRepository repository = githubRepositoryJpaRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("레포지토리를 찾을 수 없습니다: id=" + repoId));

        String prompt = buildPrompt(repository);
        String analysisText = llmClient.generate(prompt);

        persist(repository, analysisText);

        return new TrendAnalysisResponse(repository.getId(), repository.getFullName(), analysisText);
    }

    private void persist(GithubRepository repository, String analysisText) {
        TrendScore trendScore = trendScoreJpaRepository.findByRepositoryId(repository.getId())
                .orElseGet(() -> TrendScore.of(repository, 0.0));

        trendScore.updateAnalysisText(analysisText);
        trendScoreJpaRepository.save(trendScore);
    }

    // 현재 스키마에서 확보 가능한 지표만 사용: 스타/포크/오픈이슈 수,
    // 언어, 최근 갱신 시각(BaseEntity.updatedAt). 커밋 이력·릴리스
    // 빈도 같은 세부 지표는 아직 수집하지 않아(Kafka 파이프라인이
    // GitHub 검색 API 응답 필드만 저장) 프롬프트에 포함할 수 없다.
    private String buildPrompt(GithubRepository repository) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 오픈소스 트렌드 분석가입니다. 아래 GitHub 레포 지표를 보고, ");
        sb.append("이 레포가 왜 주목받고 있는지 2~3문장으로 분석하세요. ");
        sb.append("주어진 지표 밖의 사실(예: 실제 커밋 내역, 뉴스)을 지어내지 마세요.\n\n");
        sb.append("레포: ").append(repository.getFullName()).append("\n");
        if (repository.getDescription() != null && !repository.getDescription().isBlank()) {
            sb.append("설명: ").append(repository.getDescription()).append("\n");
        }
        sb.append("언어: ").append(repository.getLanguage() != null ? repository.getLanguage() : "미상").append("\n");
        sb.append("스타 수: ").append(repository.getStarCount()).append("\n");
        sb.append("포크 수: ").append(repository.getForkCount()).append("\n");
        sb.append("오픈 이슈 수: ").append(repository.getOpenIssueCount()).append("\n");
        if (repository.getUpdatedAt() != null) {
            sb.append("최근 갱신: ").append(repository.getUpdatedAt().format(DATE_FORMAT)).append("\n");
        }

        return sb.toString();
    }
}
