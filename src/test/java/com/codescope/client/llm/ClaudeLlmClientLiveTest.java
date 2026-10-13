package com.codescope.client.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Claude API를 1회 호출하는 스모크 테스트.
 *
 * 왜 따로 두는가: ClaudeLlmClientTest(가짜 서버)는 "우리가 보내는 JSON 모양"까지만
 *   검증한다. 실제 API가 그 요청(특히 beta인 refusal fallback 파라미터)을
 *   받아주는지는 실제로 한 번 불러봐야 안다.
 *
 * 실행: ANTHROPIC_API_KEY=... [ANTHROPIC_BASE_URL=...] ./gradlew liveTest
 *   - "live" 태그라 기본 test 태스크(CI 포함)에서는 제외된다(build.gradle).
 *   - 키가 없으면 자동 스킵.
 *   - 비용: 짧은 프롬프트 1회(effort=low) — 1센트 미만.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClaudeLlmClientLiveTest {

    @Test
    @DisplayName("실제 Claude API가 요청(model/effort/refusal fallback)을 받아주고 한국어 텍스트를 돌려준다")
    void generate_realApi() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String model = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-opus-5-5");
        String baseUrl = System.getenv().getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ClaudeLlmClient client = new ClaudeLlmClient(
                AnthropicOkHttpClient.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .timeout(Duration.ofSeconds(60))
                        .maxRetries(2)
                        .build(),
                new Semaphore(1), registry,
                apiKey, model, 1024, "low", true);

        String result = client.generate("한 문장으로만 답하세요: Spring Boot는 무엇인가요?");

        assertThat(result).isNotBlank();
        assertThat(registry.counter("codescope.llm.tokens", "provider", "claude", "type", "output").count())
                .isPositive();
        System.out.println("[live] baseUrl=" + baseUrl + " model=" + model + " 응답: " + result);
    }
}
