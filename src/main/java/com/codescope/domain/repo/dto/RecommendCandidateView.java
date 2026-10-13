package com.codescope.domain.repo.dto;

/**
 * pgvector 유사도 검색 결과를 엔티티가 아니라 필요한 컬럼만 바로 받는
 * Spring Data 인터페이스 프로젝션(RepoEmbeddingJpaRepository.findNearestEmbeddedCandidates).
 *
 * 왜 엔티티(RepoEmbedding) 대신인가:
 *   1) N+1 제거: RepoEmbedding.repository는 LAZY라 후보 10개를 DTO로 바꾸는
 *      순간 github_repository SELECT가 10번 더 나갔다. JOIN 결과에서 바로
 *      컬럼을 꺼내면 쿼리 1번으로 끝난다.
 *   2) 트랜잭션 범위 축소: 엔티티를 넘기면 지연 로딩을 위해 서비스 전체를
 *      트랜잭션으로 감싸야 했고, 그 트랜잭션이 LLM 응답을 기다리는 수 초 동안
 *      DB 커넥션을 점유해 풀이 고갈됐다(docs/performance/loadtest-guide.md
 *      기준선 실측). 값만 받으면 검색 쿼리가 끝나는 즉시 커넥션이 반환된다.
 */
public interface RecommendCandidateView {

    Long getId();

    String getFullName();

    String getDescription();

    String getLanguage();

    int getStarCount();
}
