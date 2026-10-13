package com.codescope.client.llm;

import com.anthropic.client.AnthropicClient;
import com.codescope.infra.config.ClaudeSemaphoreConfig;
import com.codescope.infra.config.OllamaSemaphoreConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * llm.generation.provider / llm.embedding.provider 조합마다 LlmClient와
 * EmbeddingService가 정확히 하나씩, 의도한 구현체로 등록되는지 검증한다.
 *
 * 왜 @SpringBootTest가 아니라 ApplicationContextRunner인가: 전체 컨텍스트는
 *   Postgres/Redis가 있어야 뜬다. 여기서 보려는 건 @ConditionalOnProperty
 *   배선뿐이라, LLM 관련 Bean만 골라 올리면 인프라 없이 수백 ms 안에 검증된다.
 *   배선이 틀리면(구현체 0개 또는 2개) 실제 앱 기동 시점에야
 *   NoSuchBeanDefinition/NoUniqueBeanDefinition으로 터지는데, 그걸 CI에서 미리 잡는다.
 */
class LlmProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(
                    ClaudeSemaphoreConfig.class, OllamaSemaphoreConfig.class,
                    OllamaRestClientConfig.class, ClaudeClientConfig.class,
                    ClaudeLlmClient.class, OllamaLlmClient.class, StubLlmClient.class,
                    OllamaEmbeddingService.class, StubEmbeddingService.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues(
                    "codescope.claude-semaphore.permits=4",
                    "codescope.claude-semaphore.acquire-timeout-ms=5000",
                    "codescope.claude-semaphore.retry-after-seconds=5",
                    "codescope.ollama-semaphore.permits=4",
                    "llm.claude.base-url=https://api.anthropic.com",
                    "llm.claude.model=claude-opus-5-5",
                    "llm.claude.effort=low",
                    "llm.claude.max-tokens=4096",
                    "llm.claude.timeout-ms=60000",
                    "llm.claude.max-retries=2",
                    "llm.claude.refusal-fallback=true",
                    "llm.stub.generation-latency-ms=0",
                    "llm.stub.embedding-latency-ms=0",
                    "llm.stub.embedding-dimension=768",
                    "llm.ollama.base-url=http://localhost:11434",
                    "llm.ollama.embedding-model=nomic-embed-text",
                    "llm.ollama.connect-timeout-ms=3000",
                    "llm.ollama.read-timeout-ms=20000",
                    "llm.ollama.generation.model=llama3.2:3b",
                    "llm.ollama.generation.connect-timeout-ms=3000",
                    "llm.ollama.generation.read-timeout-ms=200000",
                    "llm.ollama.generation.max-tokens=200");

    @Test
    @DisplayName("provider 미지정(기본값): 생성=Claude, 임베딩=Ollama — API 키가 없어도 컨텍스트는 뜬다")
    void defaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(LlmClient.class).isInstanceOf(ClaudeLlmClient.class);
            assertThat(context).getBean(EmbeddingService.class).isInstanceOf(OllamaEmbeddingService.class);
            assertThat(context).hasSingleBean(AnthropicClient.class);
        });
    }

    @Test
    @DisplayName("stub/stub(부하테스트): Claude 클라이언트 Bean 자체가 만들어지지 않는다")
    void stub() {
        runner.withPropertyValues("llm.generation.provider=stub", "llm.embedding.provider=stub")
                .run(context -> {
                    assertThat(context).getBean(LlmClient.class).isInstanceOf(StubLlmClient.class);
                    assertThat(context).getBean(EmbeddingService.class).isInstanceOf(StubEmbeddingService.class);
                    assertThat(context).doesNotHaveBean(AnthropicClient.class);
                });
    }

    @Test
    @DisplayName("ollama: 기존 Ollama 생성 구현체로 되돌릴 수 있다")
    void ollama() {
        runner.withPropertyValues("llm.generation.provider=ollama")
                .run(context -> {
                    assertThat(context).getBean(LlmClient.class).isInstanceOf(OllamaLlmClient.class);
                    assertThat(context).doesNotHaveBean(AnthropicClient.class);
                });
    }
}
