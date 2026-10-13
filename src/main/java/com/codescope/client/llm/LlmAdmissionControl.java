package com.codescope.client.llm;

import com.codescope.common.exception.LlmOverloadedException;
import io.micrometer.core.instrument.Counter;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * LLM 호출 입장 제어: 세마포어로 동시 호출 수를 제한하되, 대기에 상한을 둔다.
 *
 * 왜 무한 대기(acquire)를 버렸는가(부하테스트 실측): 트랜잭션 경계 수정 후에도
 *   LLM 경로는 처리 상한(permits 4 / 생성 2초 ≈ 초당 2건)보다 유입(초당 5건+)이
 *   많으면 대기열이 끝없이 쌓였다(claudeSemaphore 대기 최대 171, 추천 p95 57초).
 *   사용자는 응답이 올지도 모른 채 1분을 기다리고, 서버는 그동안 요청 객체와
 *   가상 스레드를 쌓아 둔다. 상한을 넘긴 요청은 즉시 503으로 돌려보내는 편이
 *   사용자(재시도 시점을 안다)에게도 서버(대기열이 유한하다)에게도 낫다.
 *
 * 왜 대기 상한을 0(즉시 거절)으로 두지 않는가: 순간적인 몰림은 수 초 안에
 *   풀린다. 짧게 기다려 줄 수 있는 요청까지 거절하면 처리 가능한 요청을 버리게 된다.
 *
 * 사용: acquire() 성공 후에는 반드시 finally에서 release()를 호출한다
 * (acquire를 try 블록 바깥에서 하는 이유는 DbSemaphoreConfig 참고).
 */
public class LlmAdmissionControl {

    private final Semaphore semaphore;
    private final long acquireTimeoutMs;
    private final long retryAfterSeconds;
    private final Counter shedCounter;

    public LlmAdmissionControl(Semaphore semaphore, long acquireTimeoutMs,
                               long retryAfterSeconds, Counter shedCounter) {
        this.semaphore = semaphore;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.retryAfterSeconds = retryAfterSeconds;
        this.shedCounter = shedCounter;
    }

    public void acquire() {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 세마포어 획득 중 인터럽트됨", e);
        }

        if (!acquired) {
            shedCounter.increment();
            throw new LlmOverloadedException(retryAfterSeconds);
        }
    }

    public void release() {
        semaphore.release();
    }
}
