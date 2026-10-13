package com.codescope.common.exception;

import lombok.Getter;

/**
 * LLM 호출 대기열이 상한을 넘어 요청을 받지 않을 때(load shedding) 던진다.
 * GlobalExceptionHandler가 503 Service Unavailable + Retry-After로 변환한다.
 *
 * 왜 500이 아니라 503인가: 서버 고장이 아니라 "지금은 바쁘니 잠시 후 다시"라는
 * 일시적 상태다. 503 + Retry-After는 클라이언트(프론트, 재시도 로직)가 언제
 * 다시 시도하면 되는지 기계적으로 알 수 있는 표준 신호다.
 */
@Getter
public class LlmOverloadedException extends RuntimeException {

    private final long retryAfterSeconds;

    public LlmOverloadedException(long retryAfterSeconds) {
        super("LLM 요청이 몰려 대기 상한을 넘었습니다");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
