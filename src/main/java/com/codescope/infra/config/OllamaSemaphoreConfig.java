package com.codescope.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

/**
 * Ollama API 호출(임베딩 + 생성) 배압(backpressure) 제어용 세마포어.
 *
 * 왜 DB 세마포어(DbSemaphoreConfig)와 별도 Bean인가:
 *   Day 16에서 확정한 설계 근거를 그대로 따른다 — Ollama·GitHub API·
 *   PostgreSQL은 서로 다른 하류 시스템이고 처리 한계도 다르다. 세마포어를
 *   공유하면 한 시스템의 배압이 무관한 다른 시스템 호출까지 막아버리고,
 *   지연이 발생했을 때 어느 하류가 병목인지도 구분할 수 없다. 독립된
 *   Bean으로 분리해야 병목 지점을 따로 관찰·튜닝할 수 있다.
 *   (GitHub API용 세마포어는 이번 Day 25 스코프 밖 — 아직 미구현)
 *
 * 왜 임베딩(OllamaEmbeddingService)과 생성(OllamaLlmClient)이 이 Bean
 * 하나를 공유하는가(2026-08-16, Day 26+27 통합 구현 시 결정):
 *   위 "서로 다른 하류 시스템은 세마포어를 분리한다" 원칙과 얼핏 배치돼
 *   보이지만, 그 원칙은 "GitHub API vs OpenAI"처럼 rate limit이 서로
 *   다른 독립된 외부 서비스를 구분하기 위한 것이었다. 임베딩과 생성은
 *   서로 다른 서비스가 아니라 로컬에 떠 있는 **동일한 Ollama 프로세스
 *   하나**를 두드린다 — 같은 CPU/메모리를 두고 경합하는 같은 자원이다.
 *   여기서 세마포어를 둘로 나누면 "Ollama 전체가 감당 가능한 동시
 *   요청 수"라는 실제 제약과 무관하게 permits 합만 늘어나(예: 임베딩
 *   4 + 생성 2 = 실질 동시 6개까지 허용), 정작 막으려던 자원 경합이
 *   그대로 재현된다. 하나의 Bean으로 "이 로컬 Ollama 인스턴스에 대한
 *   전체 동시 요청 상한"을 표현하는 것이 원칙의 취지(하류 시스템 단위로
 *   분리)에 더 부합한다.
 *
 * 왜 permits 기본값 4인가(2026-08-15 실측 기록 - docs/troubleshooting.md
 * Day 26 참고): 원래 잠정치는 2였으나, concurrency=3인 EmbedConsumer와
 * 맞지 않아 poll 스레드가 오래 묶여 Kafka 리밸런싱 폭주로 이어짐을
 * 확인 후 4로 상향.
 *
 * 사용 방법은 DbSemaphoreConfig와 동일하다(acquire는 try 블록 바깥에서,
 * release는 반드시 finally에서).
 */
@Slf4j
@Configuration
public class OllamaSemaphoreConfig {

    @Bean
    public Semaphore ollamaSemaphore(
            @Value("${codescope.ollama-semaphore.permits:2}") int permits
    ) {
        log.info("Ollama 세마포어 초기화(임베딩+생성 공유): permits={}", permits);

        // fair 모드(true): DB 세마포어와 동일한 이유로, 처리량보다 대기
        // 시간의 예측 가능성을 우선한다.
        return new Semaphore(permits, true);
    }
}
