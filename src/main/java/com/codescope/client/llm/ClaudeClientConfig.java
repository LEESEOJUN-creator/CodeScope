package com.codescope.client.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

// Claude API 클라이언트(공식 Java SDK) 등록. llm.generation.provider=claude일
// 때만 뜬다(기본값) — ollama/stub 모드에서는 API 키 없이도 앱이 기동해야 하므로.
//
// 왜 API 키가 비어 있어도 기동은 막지 않는가: CI(@SpringBootTest 컨텍스트
//   로딩)에는 키가 없다. 키 누락은 "실제 생성 호출 시점"에 ClaudeLlmClient가
//   명확한 메시지로 실패시키고, 여기서는 경고 로그만 남긴다.
@Slf4j
@Configuration
@ConditionalOnProperty(name = "llm.generation.provider", havingValue = "claude", matchIfMissing = true)
public class ClaudeClientConfig {

    @Bean
    public AnthropicClient anthropicClient(
            @Value("${llm.claude.api-key:}") String apiKey,
            @Value("${llm.claude.base-url}") String baseUrl,
            @Value("${llm.claude.timeout-ms}") long timeoutMs,
            @Value("${llm.claude.max-retries}") int maxRetries
    ) {
        if (apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY가 설정되지 않았습니다 — Claude 생성 호출은 실패합니다. "
                    + "비용 없이 돌리려면 LLM_GENERATION_PROVIDER=stub 또는 ollama로 기동하세요.");
        }

        // 왜 SDK 기본 재시도(maxRetries)를 그대로 쓰는가: SDK가 429/5xx/연결
        //   오류만 골라 지수 백오프로 재시도한다. 400처럼 재시도해도 결과가
        //   같은 오류는 재시도하지 않으므로, 직접 재시도 루프를 짤 이유가 없다.
        // timeout은 "재시도 1회당" 상한이라, 최악의 벽시계 시간은
        //   timeout × (maxRetries + 1)이다(application.yaml 주석 참고).
        // baseUrl: 기본은 Anthropic 직접 호출. Messages API 호환 게이트웨이를
        //   거칠 때만 바꾼다(SDK가 뒤에 /v1/messages를 붙이므로 그 앞까지만 지정).
        log.info("Claude API base-url={}", baseUrl);
        return AnthropicOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey.isBlank() ? "missing-api-key" : apiKey)
                .timeout(Duration.ofMillis(timeoutMs))
                .maxRetries(maxRetries)
                .build();
    }
}
