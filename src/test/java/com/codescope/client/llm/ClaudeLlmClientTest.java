package com.codescope.client.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ClaudeLlmClient 단위 테스트 — 실제 Claude API 대신 JDK 내장 HttpServer를
 * 가짜 Messages API로 띄워 검증한다.
 *
 * 왜 Mockito로 AnthropicClient를 목킹하지 않는가: client.beta().messages()
 *   .create() 체인을 목킹하면 "우리가 SDK를 어떻게 부르는가"만 검증되고,
 *   실제로 어떤 JSON이 나가는지(fallbacks·effort·beta 헤더)는 검증되지 않는다.
 *   가짜 서버는 SDK의 직렬화/역직렬화까지 그대로 통과시켜 요청/응답의 실제
 *   모양을 검증한다. API 키·네트워크·비용이 필요 없어 CI에서도 돈다.
 */
class ClaudeLlmClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastBetaHeader = new AtomicReference<>();
    private volatile String nextResponse;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastBetaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] body = nextResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("요청에 모델·effort·refusal fallback이 실리고, 응답 텍스트와 토큰 사용량이 반영된다")
    void generate_sendsExpectedRequestAndParsesText() {
        nextResponse = messageJson("end_turn", "추천: spring-projects/spring-boot");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClaudeLlmClient client = newClient(registry, "test-key", true);

        String result = client.generate("프롬프트");

        assertThat(result).isEqualTo("추천: spring-projects/spring-boot");
        assertThat(lastRequestBody.get())
                .contains("\"model\":\"claude-opus-5-5\"")
                .contains("\"effort\":\"low\"")
                .contains("\"fallbacks\":\"default\"")
                .contains("프롬프트");
        assertThat(lastBetaHeader.get()).contains("server-side-fallback-2026-07-01");
        assertThat(registry.counter("codescope.llm.tokens", "provider", "claude", "type", "input").count())
                .isEqualTo(12.0);
        assertThat(registry.counter("codescope.llm.tokens", "provider", "claude", "type", "output").count())
                .isEqualTo(7.0);
    }

    @Test
    @DisplayName("refusal-fallback=false면 fallbacks 필드와 beta 헤더를 보내지 않는다")
    void generate_withoutFallback_omitsFallbackFields() {
        nextResponse = messageJson("end_turn", "응답");
        ClaudeLlmClient client = newClient(new SimpleMeterRegistry(), "test-key", false);

        client.generate("프롬프트");

        assertThat(lastRequestBody.get()).doesNotContain("fallbacks");
        assertThat(lastBetaHeader.get()).isNull();
    }

    @Test
    @DisplayName("stop_reason=refusal이면 content를 읽지 않고 예외로 실패한다")
    void generate_refusal_throws() {
        nextResponse = messageJson("refusal", "");
        ClaudeLlmClient client = newClient(new SimpleMeterRegistry(), "test-key", true);

        assertThatThrownBy(() -> client.generate("프롬프트"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusal");
    }

    @Test
    @DisplayName("max_tokens로 잘린 응답은 부분 응답이라도 그대로 반환한다")
    void generate_maxTokens_returnsPartialText() {
        nextResponse = messageJson("max_tokens", "잘린 응답");
        ClaudeLlmClient client = newClient(new SimpleMeterRegistry(), "test-key", true);

        assertThat(client.generate("프롬프트")).isEqualTo("잘린 응답");
    }

    @Test
    @DisplayName("API 키가 없으면 네트워크 호출 없이 즉시 실패하고, 세마포어를 점유하지 않는다")
    void generate_withoutApiKey_failsFast() {
        Semaphore semaphore = new Semaphore(1);
        ClaudeLlmClient client = new ClaudeLlmClient(
                anthropicClient(), semaphore, new SimpleMeterRegistry(),
                "", "claude-opus-5-5", 4096, "low", true);

        assertThatThrownBy(() -> client.generate("프롬프트"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
        assertThat(lastRequestBody.get()).isNull();
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("호출이 끝나면(성공이든 실패든) 세마포어 permits가 원래대로 반환된다")
    void generate_releasesSemaphore() {
        nextResponse = messageJson("refusal", "");
        Semaphore semaphore = new Semaphore(2);
        ClaudeLlmClient client = new ClaudeLlmClient(
                anthropicClient(), semaphore, new SimpleMeterRegistry(),
                "test-key", "claude-opus-5-5", 4096, "low", true);

        assertThatThrownBy(() -> client.generate("프롬프트")).isInstanceOf(IllegalStateException.class);
        assertThat(semaphore.availablePermits()).isEqualTo(2);
    }

    private ClaudeLlmClient newClient(SimpleMeterRegistry registry, String apiKey, boolean refusalFallback) {
        return new ClaudeLlmClient(
                anthropicClient(), new Semaphore(4), registry,
                apiKey, "claude-opus-5-5", 4096, "low", refusalFallback);
    }

    private AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
    }

    private static String messageJson(String stopReason, String text) {
        String content = text.isEmpty() ? "[]" : "[{\"type\":\"text\",\"text\":\"" + text + "\"}]";
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":12,"output_tokens":7}}
                """.formatted(content, stopReason);
    }
}
