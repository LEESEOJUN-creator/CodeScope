package com.codescope.domain.repo.dto;

// POST /api/test/simulate-batch-load 응답. requested-succeeded-failed로
// 세마포어가 요청을 "실패"가 아니라 "대기"로 바꿔주는지 눈으로 확인할 수 있게 한다
// (세마포어가 없다면 count가 permits를 넘는 순간부터 HikariCP connection-timeout으로
// failed가 늘어나야 정상 — DbSemaphoreConfig 주석의 설계 의도와 대조하는 용도).
public record BatchLoadResult(
        int requested,
        int succeeded,
        int failed,
        long totalElapsedMs
) {
}
