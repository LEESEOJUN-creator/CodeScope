package com.codescope.infra.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EmbedWorkerConfig.BoundedWaitPolicy 검증 — 큐가 가득 찼을 때 즉시 거절하던
 * 기존 동작(버스트 30건 중 3건이 DLT → FAILED)이 "자리가 나면 받는다"로 바뀌었는지.
 */
class EmbedWorkerConfigTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdownNow();
    }

    @Test
    @DisplayName("큐가 가득 차도 대기 시간 안에 자리가 나면 거절하지 않고 실행한다")
    void fullQueue_waitsForSpace_thenAccepts() throws Exception {
        executor = new EmbedWorkerConfig().embedWorkerExecutor(1, 1, 5_000);
        fillPoolAndQueue();

        // 300ms 뒤에 워커를 풀어 큐에 자리가 나게 한다
        Thread.ofVirtual().start(() -> {
            sleep(300);
            release.countDown();
        });

        AtomicBoolean ran = new AtomicBoolean(false);
        long start = System.nanoTime();
        executor.execute(() -> ran.set(true));
        long waitedMs = (System.nanoTime() - start) / 1_000_000;

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran).isTrue();
        assertThat(waitedMs).as("자리가 날 때까지 제출 스레드가 기다렸다").isGreaterThanOrEqualTo(200);
    }

    @Test
    @DisplayName("대기 상한 안에 자리가 나지 않으면 RejectedExecutionException — 재시도/DLT 경로로 넘어간다")
    void fullQueue_noSpaceWithinTimeout_rejects() {
        executor = new EmbedWorkerConfig().embedWorkerExecutor(1, 1, 200);
        fillPoolAndQueue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(150);
    }

    @Test
    @DisplayName("종료된 풀에는 기다리지 않고 즉시 거절한다")
    void shutdownPool_rejectsImmediately() {
        executor = new EmbedWorkerConfig().embedWorkerExecutor(1, 1, 5_000);
        executor.shutdown();

        long start = System.nanoTime();
        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);
    }

    // 워커 1개는 release까지 블로킹, 큐 1칸은 대기 작업으로 채운다
    private void fillPoolAndQueue() {
        executor.execute(this::awaitRelease);
        executor.execute(this::awaitRelease);
    }

    private void awaitRelease() {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
