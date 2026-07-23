package com.codescope.domain.repo.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DuplicateCheckService.tryLock()의 동시성 제어 검증 테스트
 * 실제 Redis(kind Redis Pod, port-forward된 localhost:6379)에 연결해서,
 * 동시에 여러 스레드가 같은 identifier로 락을 시도했을 때 딱 하나만
 * 성공하는지(setIfAbsent의 원자성) 확인한다
 */
@SpringBootTest
class DuplicateCheckServiceTest {

    private static final String TEST_IDENTIFIER = "test-repo-concurrency";
    private static final int THREAD_COUNT = 10;

    @Autowired
    private DuplicateCheckService duplicateCheckService;

    @AfterEach
    void tearDown() {
        duplicateCheckService.releaseLock(TEST_IDENTIFIER);
    }

    @Test
    @DisplayName("동일한 identifier로 스레드 10개가 동시에 tryLock을 호출하면 정확히 1개만 성공한다")
    void tryLock_동시_요청_중_하나만_성공() throws InterruptedException {
        // given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);

        // when
        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    if (duplicateCheckService.tryLock(TEST_IDENTIFIER)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        // then
        assertThat(successCount.get()).isEqualTo(1);
    }
}
