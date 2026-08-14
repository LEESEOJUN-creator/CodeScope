package com.codescope.client.llm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 로컬 Ollama(127.0.0.1:11434, nomic-embed-text)를 호출하는 통합 테스트.
 *
 * 왜 Spring 컨텍스트(@SpringBootTest)를 쓰지 않는가: 전체 컨텍스트를
 * 띄우면 이 테스트와 무관한 DB/Kafka/Redis까지 필요해져, 그 인프라도
 * Ollama도 없는 CI에서 컨텍스트 로딩 자체가 실패한다. CI의 postgres
 * 서비스 컨테이너 문제(V3 pgvector 확장 누락)와 같은 종류의 환경 불일치를
 * 또 만들지 않기 위해, OllamaEmbeddingService를 직접 new로 생성해
 * 필요한 협력 객체(RestClient, Semaphore)만 수동으로 준비한다.
 *
 * 왜 @EnabledIfSystemProperty 대신 런타임 reachability 체크인가:
 * 시스템 프로퍼티는 로컬에서 매번 수동으로 켜야 해서 잊기 쉽다. 대신
 * 테스트 시작 시 11434 포트에 실제로 연결해보고, 실패하면
 * Assumptions.abort()로 스킵한다(JUnit5에서 "skipped"로 표시되고 빌드를
 * 깨지 않는다) — Ollama가 떠 있으면 자동으로 돌고, 없으면(CI 포함)
 * 자동으로 스킵된다.
 */
class OllamaEmbeddingServiceIntegrationTest {

    private static final String BASE_URL = "http://localhost:11434";
    private static final String MODEL = "nomic-embed-text";

    @BeforeAll
    static void checkOllamaReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 11434), 500);
        } catch (IOException e) {
            Assumptions.abort(
                    "로컬 Ollama(127.0.0.1:11434)에 연결할 수 없어 테스트를 스킵합니다: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("짧은 더미 README를 실제 Ollama로 임베딩하면 768차원 벡터가 반환된다")
    void embed_returns768DimVector() {
        // given
        RestClient restClient = RestClient.builder().baseUrl(BASE_URL).build();
        Semaphore semaphore = new Semaphore(2, true);
        OllamaEmbeddingService embeddingService =
                new OllamaEmbeddingService(restClient, semaphore, MODEL);

        String dummyReadme = """
                # CodeScope
                GitHub 트렌드를 실시간으로 분석하고 AI가 기여 가능한 프로젝트를
                추천해주는 플랫폼입니다. pgvector로 코사인 유사도를 검색합니다.
                """;

        // when
        float[] embedding = embeddingService.embed("owner/dummy-repo", dummyReadme);

        // then
        assertThat(embedding).hasSize(768);
        // 전부 0인 벡터는 Ollama가 실제로 응답하지 않고 빈 값만 채운 경우라
        // 정상 임베딩이라 보기 어렵다.
        boolean hasNonZeroValue = false;
        for (float v : embedding) {
            if (v != 0f) {
                hasNonZeroValue = true;
                break;
            }
        }
        assertThat(hasNonZeroValue).isTrue();
    }
}
