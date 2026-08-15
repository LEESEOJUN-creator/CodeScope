package com.codescope.client.llm;

/**
 * README 텍스트를 벡터로 변환하는 임베딩 서비스.
 * 구현체 분리(OllamaEmbeddingService/향후 OpenAI 등)로, 호출부(EmbedConsumer)는
 * 어떤 모델을 쓰는지 몰라도 되도록 한다.
 */
public interface EmbeddingService {

    /**
     * @param repoFullName 로그/예외 메시지 식별용(owner/repo)
     * @param readmeText   임베딩할 README 원문
     * @return 레포 1개를 대표하는 벡터(청크 분할 시 평균)
     */
    float[] embed(String repoFullName, String readmeText);

    /**
     * 검색 질의(사용자 스택 텍스트 등)를 임베딩한다.
     *
     * 왜 embed()와 분리된 메서드인가(2026-08-16, Day 26+27): embed()는
     * "저장할 문서"용 prefix("search_document: ")가 구현체
     * 내부에 하드코딩돼 있다(OllamaEmbeddingService 참고). nomic-embed-text는
     * 문서와 질의를 서로 다른 벡터 공간으로 학습한 모델이라, 검색
     * 질의에 문서 prefix를 붙이면 두 공간이 뒤섞여 유사도 검색 정확도가
     * 떨어진다. RepoRecommendService처럼 "질의 → 유사 문서 검색"이
     * 목적인 호출부는 반드시 이 메서드를 써야 한다.
     *
     * @param queryText 검색 질의 원문(예: "Java, Spring Boot, Kafka")
     * @return 질의를 대표하는 벡터
     */
    float[] embedQuery(String queryText);
}
