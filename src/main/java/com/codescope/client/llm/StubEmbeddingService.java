package com.codescope.client.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import java.util.concurrent.Semaphore;

/**
 * 부하테스트 전용 EmbeddingService(llm.embedding.provider=stub).
 * StubLlmClient와 같은 이유로, 실제 임베딩 모델 없이 지연 모양만 재현한다.
 *
 * 왜 랜덤이 아니라 텍스트 해시 기반 결정적 벡터인가: 같은 질의는 항상 같은
 *   벡터가 나와야 pgvector 검색 결과(=DB 부하 모양)가 실행마다 흔들리지
 *   않는다. 벡터 값 자체는 의미가 없으므로 추천 "품질"은 검증 대상이 아니다.
 *
 * 왜 단위 벡터로 정규화하는가: 실제 임베딩 모델 출력처럼 크기가 1이어야
 *   코사인 거리 연산이 실제와 같은 범위의 값을 다룬다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.embedding.provider", havingValue = "stub")
public class StubEmbeddingService implements EmbeddingService {

    private final Semaphore ollamaSemaphore;
    private final long latencyMs;
    private final int dimension;

    public StubEmbeddingService(
            @Qualifier("ollamaSemaphore") Semaphore ollamaSemaphore,
            @Value("${llm.stub.embedding-latency-ms}") long latencyMs,
            @Value("${llm.stub.embedding-dimension}") int dimension
    ) {
        this.ollamaSemaphore = ollamaSemaphore;
        this.latencyMs = latencyMs;
        this.dimension = dimension;
        log.warn("StubEmbeddingService 활성화 — 실제 임베딩 모델을 호출하지 않습니다(부하테스트 전용). "
                + "latencyMs={}, dimension={}", latencyMs, dimension);
    }

    @Override
    public float[] embed(String repoFullName, String readmeText) {
        if (readmeText == null || readmeText.isBlank()) {
            throw new IllegalStateException("임베딩할 README가 비어있습니다: fullName=" + repoFullName);
        }
        return simulate(readmeText);
    }

    @Override
    public float[] embedQuery(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalStateException("임베딩할 검색 질의가 비어있습니다");
        }
        return simulate(queryText);
    }

    private float[] simulate(String text) {
        try {
            ollamaSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("임베딩 세마포어 획득 중 인터럽트됨", e);
        }

        try {
            Thread.sleep(latencyMs);
            return deterministicUnitVector(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("stub 임베딩 대기 중 인터럽트됨", e);
        } finally {
            ollamaSemaphore.release();
        }
    }

    float[] deterministicUnitVector(String text) {
        SplittableRandom random = new SplittableRandom(seedOf(text));
        float[] vector = new float[dimension];
        double norm = 0;
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) (random.nextDouble() * 2 - 1);
            norm += vector[i] * vector[i];
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < dimension; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    // String.hashCode()는 32비트라 충돌이 잦아, UTF-8 바이트로 64비트 FNV-1a를 만든다.
    private static long seedOf(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
