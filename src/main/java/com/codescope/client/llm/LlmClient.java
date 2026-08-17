package com.codescope.client.llm;

/**
 * 프롬프트를 받아 LLM이 생성한 텍스트를 반환하는 인터페이스.
 *
 * 왜 인터페이스로 분리하는가: OpenAI 구현체는 비용 문제로 미도입이지만,
 * 트래픽이 늘어 로컬 Ollama(단일 프로세스, 동시 처리량 제한)로는 감당이
 * 안 되는 시점이 오면 OpenAI 등으로 교체할 가능성을 열어두기 위함이다
 * (EmbeddingService/OllamaEmbeddingService와 동일한 설계 원칙). 호출부
 * (RepoRecommendService/TrendAnalysisService)는 어떤 모델을 쓰는지
 * 몰라도 되도록 한다.
 */
public interface LlmClient {

    /**
     * @param prompt LLM에 전달할 완성된 프롬프트(컨텍스트 주입까지 끝난 상태)
     * @return LLM이 생성한 텍스트
     */
    String generate(String prompt);
}
