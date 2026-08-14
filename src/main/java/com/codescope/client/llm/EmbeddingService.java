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
}
