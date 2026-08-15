package com.codescope.client.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Semaphore;

/**
 * Ollama(llama3.2:3b)로 텍스트를 생성하는 LlmClient 구현체.
 *
 * OpenAI 구현체는 비용 문제로 미도입. 인터페이스(LlmClient) 분리 이유는
 * 트래픽 증가 시 교체 가능성을 열어두기 위함(LlmClient 참고).
 */
@Slf4j
@Service
public class OllamaLlmClient implements LlmClient {

    private final RestClient ollamaGenerationRestClient;
    private final Semaphore ollamaSemaphore;
    private final String generationModel;

    // @Value를 필드가 아닌 생성자 파라미터에 붙이기 위해 OllamaEmbeddingService와
    // 동일하게 Lombok @RequiredArgsConstructor 대신 생성자를 직접 쓴다.
    public OllamaLlmClient(
            @Qualifier("ollamaGenerationRestClient") RestClient ollamaGenerationRestClient,
            @Qualifier("ollamaSemaphore") Semaphore ollamaSemaphore,
            @Value("${llm.ollama.generation.model}") String generationModel
    ) {
        this.ollamaGenerationRestClient = ollamaGenerationRestClient;
        this.ollamaSemaphore = ollamaSemaphore;
        this.generationModel = generationModel;
    }

    @Override
    public String generate(String prompt) {
        // 왜 acquire()가 try 블록 바깥인가: OllamaEmbeddingService.callOllama()와
        // 동일한 이유 — try 안에서 acquire하면 acquire 자체의 실패
        // (InterruptedException)에도 finally의 release()가 실행되어 permits
        // 카운트가 실제 획득 없이 늘어난다.
        try {
            ollamaSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama 생성 세마포어 획득 중 인터럽트됨", e);
        }

        try {
            OllamaGenerationResponse response = ollamaGenerationRestClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(OllamaGenerationRequest.of(generationModel, prompt))
                    .retrieve()
                    .body(OllamaGenerationResponse.class);

            if (response == null || response.response() == null || response.response().isBlank()) {
                throw new IllegalStateException("Ollama 생성 응답이 비어있습니다: model=" + generationModel);
            }

            return response.response();
        } finally {
            ollamaSemaphore.release();
        }
    }
}
