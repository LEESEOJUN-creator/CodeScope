package com.codescope.api.controller.test;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.repo.dto.BatchLoadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 부하 테스트 전용. DB 세마포어(DbSemaphoreConfig)가 실제로 HikariCP
 * 커넥션 풀을 보호하는지 확인하기 위한 트리거 엔드포인트.
 *
 * GET /api/repos/{id}는 의도적으로 세마포어를 거치지 않는 경로로 유지한다.
 * 세마포어가 보호하는 건 특정 요청이 아니라 HikariCP 커넥션 풀이라는 공유
 * 자원 자체이므로, 이 엔드포인트로 세마포어 보호 구간에 배치성 부하를 걸어두고
 * JMeter/k6는 별도로 GET /api/repos/{id}를 반복 호출해 "세마포어를 안 거치는
 * 가벼운 조회가 풀 고갈의 영향을 받는지"를 관찰한다.
 *
 * 세마포어 permit만 쥐고 실제 커넥션을 안 쓰면 풀에 압력이 안 생겨 실험이
 * 무의미해지므로, DataSource에서 커넥션을 직접 얻어 Postgres의 pg_sleep()으로
 * durationMs만큼 실제 점유한다. JPA/Hibernate를 거치지 않는 이유는 목적이
 * "커넥션 점유 재현"이지 실제 도메인 쿼리가 아니기 때문.
 */
@Slf4j
@Tag(name = "LoadTest", description = "부하 테스트 전용 - 운영 환경 미노출(@Profile(\"test\"))")
@RestController
@RequestMapping("/api/test")
@Profile("test")
@RequiredArgsConstructor
public class LoadTestController {

    private final Semaphore dbSemaphore;
    private final DataSource dataSource;

    @Operation(summary = "count개 동시 배치 부하를 걸고 durationMs만큼 커넥션을 점유. "
            + "useSemaphore=true(기본)면 DB 세마포어 보호 구간을 거치고, false면 세마포어 없이 바로 커넥션을 요청한다")
    @PostMapping("/simulate-batch-load")
    public ResponseEntity<ApiResponse<BatchLoadResult>> simulateBatchLoad(
            @RequestParam int count,
            @RequestParam long durationMs,
            // 세마포어 있음/없음을 같은 엔드포인트로 직접 대조하기 위한 토글.
            // false일 때 dbSemaphore.acquire()/release()를 건너뛰어, count가
            // permits(=maximumPoolSize-reserve)를 넘으면 HikariCP connection-timeout
            // (기본 30s)으로 실패가 발생하는 무방비 상태를 그대로 재현한다.
            @RequestParam(defaultValue = "true") boolean useSemaphore
    ) throws InterruptedException {
        log.info("배치 부하 시뮬레이션 시작: count={}, durationMs={}, useSemaphore={}", count, durationMs, useSemaphore);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(count);
        long start = System.nanoTime();

        // 가상 스레드: count가 permits(HikariCP maximumPoolSize - reserve)보다
        // 훨씬 커도 스레드 자체는 가볍게 뜨고, 세마포어에서 자연스럽게 대기한다
        // (CLAUDE.md 가상 스레드 설계 원칙과 동일한 이유).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                executor.submit(() -> {
                    // acquire()는 반드시 try 바깥 — DbSemaphoreConfig 클래스 주석의
                    // 확정된 사용 규칙(acquire 실패 시 finally의 release가 permit을
                    // 실제 획득 없이 늘리는 걸 방지)을 이 호출부에서도 그대로 지킨다.
                    // useSemaphore=false면 이 블록 자체를 건너뛰어 세마포어 없이
                    // 바로 DB 커넥션을 요청하는 무방비 상태를 재현한다.
                    if (useSemaphore) {
                        try {
                            dbSemaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failed.incrementAndGet();
                            latch.countDown();
                            return;
                        }
                    }

                    try (Connection connection = dataSource.getConnection();
                         Statement statement = connection.createStatement()) {
                        statement.execute("SELECT pg_sleep(" + (durationMs / 1000.0) + ")");
                        succeeded.incrementAndGet();
                    } catch (Exception e) {
                        log.warn("배치 부하 작업 실패: {}", e.getMessage());
                        failed.incrementAndGet();
                    } finally {
                        if (useSemaphore) {
                            dbSemaphore.release();
                        }
                        latch.countDown();
                    }
                });
            }

            // 전체 count가 끝날 때까지 대기하되, 무한정 기다리지는 않는다
            // (permits보다 count가 훨씬 크면 이론상 오래 걸릴 수 있어 넉넉한 상한을 둠).
            boolean finishedInTime = latch.await(5, TimeUnit.MINUTES);
            if (!finishedInTime) {
                log.warn("배치 부하 시뮬레이션이 5분 안에 끝나지 않았습니다(count={} 대비 permits가 너무 적을 수 있음)", count);
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("배치 부하 시뮬레이션 종료: succeeded={}, failed={}, elapsedMs={}",
                succeeded.get(), failed.get(), elapsedMs);

        return ResponseEntity.ok(ApiResponse.success(
                new BatchLoadResult(count, succeeded.get(), failed.get(), elapsedMs)));
    }
}
