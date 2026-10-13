package com.codescope.client.llm;

import com.codescope.common.exception.LlmOverloadedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmAdmissionControlTest {

    private final Counter shed = new SimpleMeterRegistry().counter("codescope.llm.shed");

    @Test
    @DisplayName("permits가 남아 있으면 즉시 입장하고, release하면 permits가 돌아온다")
    void acquire_withFreePermit() {
        Semaphore semaphore = new Semaphore(1);
        LlmAdmissionControl admission = new LlmAdmissionControl(semaphore, 1_000, 5, shed);

        admission.acquire();
        assertThat(semaphore.availablePermits()).isZero();

        admission.release();
        assertThat(semaphore.availablePermits()).isEqualTo(1);
        assertThat(shed.count()).isZero();
    }

    @Test
    @DisplayName("대기 상한 안에 permits가 나지 않으면 LlmOverloadedException(Retry-After 포함)으로 거절하고 카운트한다")
    void acquire_timeout_sheds() {
        Semaphore semaphore = new Semaphore(0);
        LlmAdmissionControl admission = new LlmAdmissionControl(semaphore, 200, 7, shed);

        long start = System.nanoTime();
        assertThatThrownBy(admission::acquire)
                .isInstanceOf(LlmOverloadedException.class)
                .satisfies(e -> assertThat(((LlmOverloadedException) e).getRetryAfterSeconds()).isEqualTo(7));
        long waitedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(waitedMs).as("즉시가 아니라 상한까지 기다린 뒤 거절").isGreaterThanOrEqualTo(150);
        assertThat(shed.count()).isEqualTo(1.0);
        assertThat(semaphore.availablePermits()).as("거절된 요청은 permits를 건드리지 않는다").isZero();
    }

    @Test
    @DisplayName("대기 중 다른 요청이 release하면 상한 전에 입장한다")
    void acquire_waitsForRelease() throws Exception {
        Semaphore semaphore = new Semaphore(0);
        LlmAdmissionControl admission = new LlmAdmissionControl(semaphore, 5_000, 5, shed);

        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            semaphore.release();
        });

        admission.acquire();
        assertThat(shed.count()).isZero();
    }
}
