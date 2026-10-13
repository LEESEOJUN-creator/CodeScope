package com.codescope.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * EmbedConsumer의 실제 임베딩 작업(README 다운로드 + 청킹 + Ollama 호출 +
 * DB 저장)을 poll 스레드에서 분리해 수행하는 전용 워커 풀.
 *
 * 왜 필요한가(docs/troubleshooting.md 참고):
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
 * 빨아들여 메모리를 소진할 수 있다.
 *
 * 큐가 가득 찼을 때(BoundedWaitPolicy): 즉시 거절하지 않고 submit-timeout-ms
 * 동안 큐에 자리가 나기를 기다린다. 그래도 자리가 없을 때만
 * RejectedExecutionException을 던지고, 이 예외는 EmbedConsumer.consume()에서
 * 그대로 전파되어 기존 @RetryableTopic 배선(재시도 토픽 → 최종 DLT)으로 간다.
 *   왜 즉시 거절(기존 AbortPolicy)을 버렸는가(실측): 수집 1사이클(30건)이
 *   한꺼번에 들어오면 pool 6 + queue 20 = 26을 넘는다. 거절된 메시지의 재시도
 *   백오프는 1s·2s(총 약 3초)인데 큐가 실제로 비는 데는 약 37초가 걸려, 재시도를
 *   다 소진하고 DLT → FAILED로 확정됐다(2026-10-13, 30건 중 3건). 일시적인
 *   버스트가 영구 실패가 되는 구조였다. 기다리면 큐는 결국 빈다.
 *   왜 무한정 기다리지 않는가: 기다리는 동안 poll 스레드가 멈춘다. 대기 상한 ×
 *   한 번에 받는 레코드 수(EmbedConsumer의 max.poll.records)가
 *   max.poll.interval.ms(15분)를 넘지 않아야 컨슈머가 그룹에서 쫓겨나지 않는다
 *   (60초 × 10건 = 10분). 워커가 정말로 멈춘 경우엔 대기 상한에서 거절해
 *   재시도/DLT로 넘긴다.
 * "임베딩 처리 자체의 실패"는 워커 스레드 안에서 별도로 재시도한다(EmbedConsumer 참고).
 *
 * 왜 pool-size 기본값 6인가: Ollama 세마포어(permits=4, 실측 확정치)가
 * 실제 동시 호출 수를 이미 제한하고 있으므로, 워커
 * 스레드 자체는 세마포어보다 약간 많게 둬 세마포어 대기 중인 스레드가
 * 있어도 다른 작업을 계속 받을 수 있게 한다.
 */
@Slf4j
@Configuration
public class EmbedWorkerConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService embedWorkerExecutor(
            @Value("${codescope.embed-worker.pool-size:6}") int poolSize,
            @Value("${codescope.embed-worker.queue-capacity:20}") int queueCapacity,
            @Value("${codescope.embed-worker.submit-timeout-ms:60000}") long submitTimeoutMs
    ) {
        log.info("임베딩 워커 풀 초기화: poolSize={}, queueCapacity={}, submitTimeoutMs={}",
                poolSize, queueCapacity, submitTimeoutMs);

        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new EmbedWorkerThreadFactory(),
                new BoundedWaitPolicy(submitTimeoutMs)
        );
    }

    // 큐가 가득 찼을 때 제출 스레드(= Kafka poll 스레드)를 timeout까지 블로킹해
    // 자리가 나면 넣고, 끝내 자리가 없으면 거절한다. 거절 정책은 제출 스레드에서
    // 실행되므로 별도 스레드 없이 "제출 측 배압"이 된다.
    static class BoundedWaitPolicy implements RejectedExecutionHandler {

        private final long timeoutMs;

        BoundedWaitPolicy(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            // 종료 중인 풀의 큐에 넣으면 영영 실행되지 않는다 — 즉시 거절해 재시도 경로로.
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("임베딩 워커 풀이 종료됨");
            }
            try {
                if (!executor.getQueue().offer(task, timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new RejectedExecutionException(
                            "임베딩 작업 큐가 " + timeoutMs + "ms 동안 비지 않음");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("임베딩 작업 큐 대기 중 인터럽트됨", e);
            }
        }
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
