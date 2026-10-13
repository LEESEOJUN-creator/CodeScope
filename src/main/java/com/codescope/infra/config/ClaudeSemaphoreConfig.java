package com.codescope.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

/**
 * Claude API(텍스트 생성) 호출 배압 제어용 세마포어.
 *
 * 왜 Ollama 세마포어와 분리하는가: Ollama 시절에는 임베딩과 생성이 같은
 *   로컬 Ollama 프로세스를 두드려서 세마포어 하나를 공유했다
 *   (OllamaSemaphoreConfig 참고). 생성이 Claude API로 넘어가면서 둘은
 *   서로 다른 하류 시스템이 됐다 — rate limit도, 지연 특성도 다르다.
 *   CLAUDE.md의 "세마포어 이원화" 원칙대로 Bean을 분리해야 어느 쪽이
 *   병목인지 Grafana(codescope_semaphore_* 지표)에서 따로 관찰할 수 있다.
 *
 * 왜 permits가 rate limit을 직접 대신하지 못하는가: 세마포어는 "동시에
 *   진행 중인 요청 수"를 막을 뿐 "분당 요청 수"를 막지는 않는다. 다만
 *   동시 요청 수 × 평균 응답시간이 곧 처리량 상한이 되므로, 계정 rate
 *   limit(RPM/TPM)에 맞춰 permits를 조정하면 429를 사전에 줄일 수 있다.
 *   그래도 429가 나면 SDK 재시도(ClaudeClientConfig)가 받는다.
 *
 * StubLlmClient도 이 Bean을 쓴다 — 부하테스트에서 실제 API 비용 없이
 * "Claude 호출 경로의 배압 동작"을 그대로 재현하기 위함.
 *
 * 사용 방법은 DbSemaphoreConfig와 동일하다(acquire는 try 블록 바깥에서,
 * release는 반드시 finally에서).
 */
@Slf4j
@Configuration
public class ClaudeSemaphoreConfig {

    @Bean
    public Semaphore claudeSemaphore(
            @Value("${codescope.claude-semaphore.permits}") int permits
    ) {
        log.info("Claude 세마포어 초기화(생성 전용): permits={}", permits);

        // fair 모드(true): DB/Ollama 세마포어와 동일한 이유로, 처리량보다
        // 대기 시간의 예측 가능성을 우선한다.
        return new Semaphore(permits, true);
    }
}
