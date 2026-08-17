package com.codescope.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

/**
 * Ollama API 호출(임베딩 + 생성) 배압(backpressure) 제어용 세마포어.
 *
 * 왜 DB 세마포어(DbSemaphoreConfig)와 별도 Bean인가: Ollama·GitHub API·
 *   PostgreSQL은 서로 다른 하류 시스템이고 처리 한계도 다르다. 세마포어를
 *   공유하면 한 시스템의 배압이 무관한 다른 시스템 호출까지 막고, 어느
 *   하류가 병목인지도 구분할 수 없다. 독립된 Bean으로 분리해야 병목
 *   지점을 따로 관찰·튜닝할 수 있다.
 *
 * 왜 임베딩(OllamaEmbeddingService)과 생성(OllamaLlmClient)이 이 Bean
 * 하나를 공유하는가: 위 원칙과 얼핏 배치돼 보이지만, 그 원칙은 rate
 *   limit이 서로 다른 독립된 외부 서비스를 구분하기 위한 것이다. 임베딩과
 *   생성은 로컬에 떠 있는 동일한 Ollama 프로세스 하나를 두드리는 같은
 *   자원이라, 세마포어를 둘로 나누면 permits 합만 늘어나(예: 4+2=6)
 *   막으려던 자원 경합이 그대로 재현된다.
 *
 * 왜 permits 기본값 4인가(docs/troubleshooting.md 참고): 원래 잠정치는
 * 2였으나, concurrency=3인 EmbedConsumer와 맞지 않아 poll 스레드가 오래
 * 묶여 Kafka 리밸런싱 폭주로 이어짐을 확인 후 4로 상향.
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
