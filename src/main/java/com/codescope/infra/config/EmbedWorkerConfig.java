package com.codescope.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * EmbedConsumer의 실제 임베딩 작업(README 다운로드 + 청킹 + Ollama 호출 +
 * DB 저장)을 poll 스레드에서 분리해 수행하는 전용 워커 풀.
 *
 * 왜 필요한가(Day 26 트러블슈팅 참고 - docs/troubleshooting.md):
 *   기존에는 @KafkaListener 메서드(poll 스레드)가 이 작업을 전부 동기로
 *   수행했다. README가 극단적으로 긴 레포는 청크가 수십~수백 개라 총
 *   처리 시간이 max.poll.interval.ms를 넘겼고, poll()이 그만큼 오래
 *   호출되지 않으면 Kafka 컨슈머 클라이언트가 "이 컨슈머는 죽었다"고
 *   자체 판단해 하트비트를 멈추고 그룹을 이탈한다(KIP-62) — 실제로
 *   리밸런싱 폭주로 이어짐을 실측 확인했다.
 *   이 executor로 작업을 넘기면 리스너 메서드는 제출만 하고 즉시
 *   반환하므로, 처리 시간과 무관하게 poll()이 계속 빠르게 호출된다.
 *
 * 왜 bounded queue인가: 무제한 큐를 쓰면 컨슈머가 메시지를 무한정
 * 빨아들여 메모리를 소진할 수 있다. 큐가 가득 차면
 * ThreadPoolExecutor.AbortPolicy(기본값)가 RejectedExecutionException을
 * 던지는데, 이 예외는 EmbedConsumer.consume()에서 그대로 전파되어
 * 기존 @RetryableTopic 배선(재시도 토픽 → 지수 백오프 → 최종 DLT)을
 * 그대로 재사용한다 — "큐 포화로 인한 제출 실패"만 이 경로로 처리하고,
 * "임베딩 처리 자체의 실패"는 워커 스레드 안에서 별도로 재시도한다
 * (EmbedConsumer 참고).
 *
 * 왜 pool-size 기본값 6인가: Ollama 세마포어(permits=4, 2026-08-15
 * 실측 확정치)가 실제 동시 호출 수를 이미 제한하고 있으므로, 워커
 * 스레드 자체는 세마포어보다 약간 많게 둬 세마포어 대기 중인 스레드가
 * 있어도 다른 작업을 계속 받을 수 있게 한다.
 */
@Slf4j
@Configuration
public class EmbedWorkerConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService embedWorkerExecutor(
            @Value("${codescope.embed-worker.pool-size:6}") int poolSize,
            @Value("${codescope.embed-worker.queue-capacity:20}") int queueCapacity
    ) {
        log.info("임베딩 워커 풀 초기화: poolSize={}, queueCapacity={}", poolSize, queueCapacity);

        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new EmbedWorkerThreadFactory()
        );
    }

    // 기본 스레드 이름(pool-N-thread-M)만으로는 JFR/스레드 덤프에서
    // 다른 스레드풀과 구분하기 어려워, 로그·진단 편의를 위해 이름을 명시한다.
    private static class EmbedWorkerThreadFactory implements java.util.concurrent.ThreadFactory {
        private int count = 0;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "embed-worker-" + (count++));
            thread.setDaemon(true);
            return thread;
        }
    }
}
