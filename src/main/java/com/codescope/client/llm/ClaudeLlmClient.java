package com.codescope.client.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Claude API(Messages API)로 텍스트를 생성하는 LlmClient 구현체.
 *
 * 왜 교체했는가(OllamaLlmClient 대비): 로컬 CPU 추론(llama3.2:3b)은 1.3~1.9
 *   tok/s라 추천 1건에 90~160초가 걸렸고, 소형 모델 특유의 반복 생성 열화·
 *   다국어 혼입을 프롬프트/repeat_penalty로 억누르는 데 한계가 있었다
 *   (docs/troubleshooting.md 참고). 호출부(RepoRecommendService/
 *   TrendAnalysisService)는 LlmClient 인터페이스만 알기 때문에 이 클래스
 *   추가 + llm.generation.provider 설정만으로 교체된다.
 *
 * 왜 beta 엔드포인트인가: 서버 측 refusal fallback(fallbacks: "default")이
 *   beta 기능이다. Claude 모델은 안전 분류기가 요청을 거절하면 HTTP 200 +
 *   stop_reason=refusal로 응답하는데, fallback을 켜두면 서버가 거절 범주에
 *   맞는 다른 모델로 자동 재시도한다. Haiku처럼 fallback을 지원하지 않는
 *   모델로 바꿀 때는 llm.claude.refusal-fallback=false로 끈다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.generation.provider", havingValue = "claude", matchIfMissing = true)
public class ClaudeLlmClient implements LlmClient {

    private static final String REFUSAL_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient anthropicClient;
    private final LlmAdmissionControl claudeAdmission;
    private final MeterRegistry meterRegistry;
    private final boolean apiKeyConfigured;
    private final String model;
    private final long maxTokens;
    private final BetaOutputConfig.Effort effort;
    private final boolean refusalFallback;

    public ClaudeLlmClient(
            AnthropicClient anthropicClient,
            LlmAdmissionControl claudeAdmission,
            MeterRegistry meterRegistry,
            @Value("${llm.claude.api-key:}") String apiKey,
            @Value("${llm.claude.model}") String model,
            @Value("${llm.claude.max-tokens}") long maxTokens,
            @Value("${llm.claude.effort}") String effort,
            @Value("${llm.claude.refusal-fallback}") boolean refusalFallback
    ) {
        this.anthropicClient = anthropicClient;
        this.claudeAdmission = claudeAdmission;
        this.meterRegistry = meterRegistry;
        this.apiKeyConfigured = !apiKey.isBlank();
        this.model = model;
        this.maxTokens = maxTokens;
        this.effort = BetaOutputConfig.Effort.of(effort);
        this.refusalFallback = refusalFallback;
    }

    @Override
    public String generate(String prompt) {
        if (!apiKeyConfigured) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY가 설정되지 않아 Claude 생성 호출을 할 수 없습니다.");
        }

        // 대기 상한을 넘기면 LlmOverloadedException(→ 503)으로 즉시 거절된다.
        // acquire()가 try 블록 바깥인 이유: 획득에 실패했는데 finally의 release()가
        // 실행되면 permits가 실제 획득 없이 늘어난다(OllamaLlmClient와 동일).
        claudeAdmission.acquire();
        try {
            BetaMessage response = anthropicClient.beta().messages().create(buildParams(prompt));
            recordUsage(response);
            return extractText(response);
        } finally {
            claudeAdmission.release();
        }
    }

    // 왜 effort를 명시하는가: 추천/트렌드 분석은 5줄 내외의 짧은 요약이라
    //   깊은 추론이 필요 없다. effort를 낮추면 사고(thinking) 토큰과 지연이
    //   함께 줄어든다(기본값은 모델마다 달라 명시해 둔다).
    // 왜 max-tokens가 Ollama 시절(200)보다 큰가: 사고 토큰도 max_tokens 한도에
    //   포함된다. 200으로 두면 사고만 하다 본문이 잘릴 수 있어, 응답 길이는
    //   프롬프트("5줄 이내")로 제어하고 max-tokens는 넉넉한 안전 상한으로 둔다.
    private MessageCreateParams buildParams(String prompt) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .outputConfig(BetaOutputConfig.builder().effort(effort).build())
                .addUserMessage(prompt);

        if (refusalFallback) {
            builder.addBeta(REFUSAL_FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        return builder.build();
    }

    private String extractText(BetaMessage response) {
        BetaStopReason stopReason = response.stopReason().orElse(null);

        // 거절은 HTTP 200으로 온다 — content를 읽기 전에 stop_reason부터 본다.
        // fallback까지 거절된 경우라 재시도로 해결되지 않는다.
        if (BetaStopReason.REFUSAL.equals(stopReason)) {
            throw new IllegalStateException("Claude가 요청을 거절했습니다(stop_reason=refusal): model=" + model);
        }

        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining());

        if (text.isBlank()) {
            throw new IllegalStateException("Claude 생성 응답이 비어있습니다: model=" + model);
        }

        // OllamaLlmClient의 done=false와 같은 의미 — 부분 응답도 가치가 있어
        // 예외로 막지는 않되, max-tokens 튜닝 근거로 로그는 남긴다.
        if (BetaStopReason.MAX_TOKENS.equals(stopReason)) {
            log.warn("Claude 생성이 max-tokens에서 잘렸습니다: model={}, maxTokens={}", model, maxTokens);
        }

        return text;
    }

    // 토큰 사용량을 Prometheus 카운터로 남겨 Grafana에서 비용 추이를 본다
    // (codescope_llm_tokens_total{type="input|output"}).
    private void recordUsage(BetaMessage response) {
        meterRegistry.counter("codescope.llm.tokens", "provider", "claude", "type", "input")
                .increment(response.usage().inputTokens());
        meterRegistry.counter("codescope.llm.tokens", "provider", "claude", "type", "output")
                .increment(response.usage().outputTokens());
    }
}
