package com.codescope.client.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubEmbeddingServiceTest {

    private final Semaphore semaphore = new Semaphore(2);
    private final StubEmbeddingService service = new StubEmbeddingService(semaphore, 0, 768);

    @Test
    @DisplayName("같은 텍스트는 항상 같은 벡터 — 부하테스트 실행마다 pgvector 검색 결과가 흔들리지 않는다")
    void sameText_sameVector() {
        assertThat(service.embedQuery("Java, Spring Boot")).containsExactly(service.embedQuery("Java, Spring Boot"));
        assertThat(service.embedQuery("Java, Spring Boot")).isNotEqualTo(service.embedQuery("Go, Kubernetes"));
    }

    @Test
    @DisplayName("vector(768) 컬럼에 맞는 차원의 단위 벡터를 반환한다")
    void returnsUnitVectorOfConfiguredDimension() {
        float[] vector = service.embed("owner/repo", "README 본문");

        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        assertThat(vector).hasSize(768);
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    @DisplayName("빈 입력은 실제 구현체(OllamaEmbeddingService)와 같은 예외로 거부한다")
    void blankInput_throws() {
        assertThatThrownBy(() -> service.embed("owner/repo", " ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.embedQuery("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("호출 후 세마포어 permits가 반환된다")
    void releasesSemaphore() {
        service.embedQuery("query");
        assertThat(semaphore.availablePermits()).isEqualTo(2);
    }
}
