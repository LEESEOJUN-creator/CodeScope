package com.codescope.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

/**
 * Ollama 임베딩 API 호출 배압(backpressure) 제어용 세마포어.
 *
 * 왜 DB 세마포어(DbSemaphoreConfig)와 별도 Bean인가:
 *   Day 16에서 확정한 설계 근거를 그대로 따른다 — Ollama·GitHub API·
 *   PostgreSQL은 서로 다른 하류 시스템이고 처리 한계도 다르다. 세마포어를
 *   공유하면 한 시스템의 배압이 무관한 다른 시스템 호출까지 막아버리고,
 *   지연이 발생했을 때 어느 하류가 병목인지도 구분할 수 없다. 독립된
 *   Bean으로 분리해야 병목 지점을 따로 관찰·튜닝할 수 있다.
 *   (GitHub API용 세마포어는 이번 Day 25 스코프 밖 — 아직 미구현)
 *
 * 왜 permits 기본값 2인가: 로컬 Ollama는 단일 프로세스 하나뿐이라, 동시
 * 요청이 많아지면 CPU/GPU 자원을 두고 요청끼리 경합해 개별 응답 시간만
 * 늘어난다. DB 세마포어(DbSemaphoreConfig)와 마찬가지로 실측 전 잠정치.
 *
 * 사용 방법은 DbSemaphoreConfig와 동일하다(acquire는 try 블록 바깥에서,
 * release는 반드시 finally에서).
 */
@Slf4j
@Configuration
public class OllamaSemaphoreConfig {

    @Bean
    public Semaphore ollamaEmbeddingSemaphore(
            @Value("${codescope.ollama-semaphore.permits:2}") int permits
    ) {
        log.info("Ollama 임베딩 세마포어 초기화: permits={}", permits);

        // fair 모드(true): DB 세마포어와 동일한 이유로, 처리량보다 대기
        // 시간의 예측 가능성을 우선한다.
        return new Semaphore(permits, true);
    }
}
