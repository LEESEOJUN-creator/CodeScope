package com.codescope.infra.config;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 세마포어(dbSemaphore)의 배압 효과를 실측하는 통합 테스트.
 *
 * ⚠ 실행 전 필수 확인 사항:
 *   kind 클러스터의 PostgreSQL Pod에 kubectl port-forward가 연결되어 있어야 한다.
 *   (예: kubectl port-forward svc/postgres 5432:5432 -n codescope)
 *   application.yaml의 spring.datasource.url이 이 포트를 바라보는 실제 DB.
 *   port-forward가 끊긴 상태로 실행하면 커넥션 자체가 열리지 않아
 *   두 결과 모두 의미 없는 수치가 나온다.
 *
 * 목적: 가상 스레드 다수가 동시에 DB 커넥션을 요구할 때
 *   ① 세마포어 없이 실행 시 SQLTransientConnectionException이 실제로 발생하는지
 *   ② dbSemaphore 적용 시 그 예외가 사라지는지
 *   를 실측으로 비교한다. 목표 수치를 미리 정하지 않고, 실행 결과를 그대로 로그로 남긴다.
 *
 * HikariPoolMXBean.getThreadsAwaitingConnection()의 최댓값도 함께 샘플링한다.
 *   이 프로젝트는 로컬 앱 + kubectl port-forward로 DB(kind Pod)에 접속하는 구조라,
 *   예외 건수만으로는 "DB 커넥션 풀이 고갈된 것"인지 "port-forward 자체가
 *   병목이 된 것"인지 구분이 안 된다. getThreadsAwaitingConnection()은 JVM
 *   (HikariCP) 내부 지표라 네트워크 계층 문제에 오염되지 않으므로, 세마포어
 *   적용 전/후 이 값이 어떻게 달라지는지가 예외 건수보다 더 신뢰할 수 있는 증거다.
 *
 * 세마포어 없는 테스트는 SQLTransientConnectionException을 재현하는 것 자체가
 * 목적이므로, 예외 발생 여부에 대한 assertion은 두지 않는다 (수치는 로그로만 기록).
 */
// @Tag("load"): 기본 test 태스크에서 제외되고, ./gradlew loadTest 로만 실행된다.
// 왜: 가상 스레드 2000개를 띄우는 부하 테스트라 실행이 길고(실측 62.5초)
//   러너 성능에 따라 결과가 흔들려, 매 PR CI에서 돌리기에 적합하지 않다.
@Tag("load")
@Slf4j
@SpringBootTest
class DbBackpressureLoadTest {

    // 부하량(가상 스레드 개수). 조절 가능하도록 상수로 분리 (기본값 500).
    // 5000 지시값 -> 실행 환경 여유 메모리 부족(약 813MB)으로 2000으로 하향 조정.
    private static final int LOAD_SIZE = 2000;

    // 스레드 1개당 findById 반복 횟수. 부하를 한 순간이 아니라 일정 시간
    // 유지시켜 샘플러가 pending 최댓값을 포착할 여유를 주기 위함.
    private static final int REPEAT_PER_THREAD = 5;

    // HikariPoolMXBean.getThreadsAwaitingConnection() 샘플링 주기(ms)
    private static final long SAMPLING_INTERVAL_MS = 10;

    @Autowired
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("dbSemaphore")
    private Semaphore dbSemaphore;

    private Long testRepositoryId;

    @BeforeEach
    void setUp() {
        GithubRepository repository = GithubRepository.builder()
                .name("db-backpressure-load-test")
                .fullName("codescope-test/db-backpressure-" + UUID.randomUUID())
                .description("DB 세마포어 배압 실측용 더미 레포 (조회 부하 생성 목적, 데이터 정합성 무관)")
                .language("Java")
                .starCount(0)
                .forkCount(0)
                .openIssueCount(0)
                .githubUrl("https://github.com/codescope-test/db-backpressure")
                .build();
        testRepositoryId = githubRepositoryJpaRepository.save(repository).getId();
    }

    @AfterEach
    void tearDown() {
        githubRepositoryJpaRepository.deleteById(testRepositoryId);
    }

    @Test
    @DisplayName("세마포어 없이 가상 스레드 LOAD_SIZE개 동시 조회 -> 커넥션 풀 고갈 실측")
    void 세마포어_없이_실행() throws InterruptedException, SQLException {
        // 세마포어를 거치지 않고 바로 조회 -> HikariCP maximum-pool-size를
        // 넘어서는 동시 요구가 그대로 커넥션 풀에 도달한다.
        LoadTask noSemaphoreTask = (successCount, transientConnFailCount, otherFailCount) -> {
            try {
                for (int i = 0; i < REPEAT_PER_THREAD; i++) {
                    githubRepositoryJpaRepository.findById(testRepositoryId);
                }
                successCount.incrementAndGet();
            } catch (Exception e) {
                classifyAndRecord(e, transientConnFailCount, otherFailCount);
            }
        };

        LoadResult result = runLoad("세마포어 없음", noSemaphoreTask);
        logResult(result);

        // 세마포어 없는 케이스는 예외 재현이 목적이므로 "예외가 없어야 한다"는
        // 검증은 하지 않는다. 작업 개수 집계만 sanity check.
        assertThat(result.successCount() + result.transientConnFailCount() + result.otherFailCount())
                .isEqualTo(LOAD_SIZE);
    }

    @Test
    @DisplayName("dbSemaphore 적용 후 동일 부하 실행 -> 커넥션 풀 고갈 방지 실측")
    void 세마포어_적용_후_실행() throws InterruptedException, SQLException {
        // DbSemaphoreConfig에 명시된 사용법 그대로 따른다:
        //   1) acquire()는 try 블록 "바깥"에서 호출
        //   2) release()는 finally에서 보장
        //   3) InterruptedException은 Thread.currentThread().interrupt()로 상태 복원
        LoadTask semaphoreTask = (successCount, transientConnFailCount, otherFailCount) -> {
            try {
                dbSemaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                otherFailCount.incrementAndGet();
                return;
            }
            try {
                for (int i = 0; i < REPEAT_PER_THREAD; i++) {
                    githubRepositoryJpaRepository.findById(testRepositoryId);
                }
                successCount.incrementAndGet();
            } catch (Exception e) {
                classifyAndRecord(e, transientConnFailCount, otherFailCount);
            } finally {
                dbSemaphore.release();
            }
        };

        LoadResult result = runLoad("세마포어 있음", semaphoreTask);
        logResult(result);

        assertThat(result.successCount() + result.transientConnFailCount() + result.otherFailCount())
                .isEqualTo(LOAD_SIZE);
    }

    /**
     * 가상 스레드 LOAD_SIZE개로 task를 동시 실행하면서
     * HikariPoolMXBean.getThreadsAwaitingConnection() 최댓값을 샘플링하고,
     * 전체 소요 시간을 측정한다.
     */
    private LoadResult runLoad(String label, LoadTask task) throws InterruptedException, SQLException {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger transientConnFailCount = new AtomicInteger();
        AtomicInteger otherFailCount = new AtomicInteger();
        AtomicInteger pendingMax = new AtomicInteger();

        HikariDataSource hikariDataSource = dataSource.unwrap(HikariDataSource.class);
        HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();

        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> {
            int pending = poolMXBean.getThreadsAwaitingConnection();
            pendingMax.updateAndGet(current -> Math.max(current, pending));
        }, 0, SAMPLING_INTERVAL_MS, TimeUnit.MILLISECONDS);

        long startNanos = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(LOAD_SIZE);
            for (int i = 0; i < LOAD_SIZE; i++) {
                executor.submit(() -> {
                    try {
                        task.execute(successCount, transientConnFailCount, otherFailCount);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } finally {
            sampler.shutdown();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        return new LoadResult(label, successCount.get(), transientConnFailCount.get(),
                otherFailCount.get(), pendingMax.get(), elapsedMs);
    }

    /**
     * 예외를 원인 체인까지 훑어 SQLTransientConnectionException(커넥션 풀 고갈)인지,
     * 그 외 예외(네트워크 오류 등 다른 원인)인지 구분해서 각각 별도로 기록한다.
     */
    private void classifyAndRecord(Exception e, AtomicInteger transientConnFailCount,
                                    AtomicInteger otherFailCount) {
        if (isTransientConnectionException(e)) {
            transientConnFailCount.incrementAndGet();
        } else {
            otherFailCount.incrementAndGet();
            log.warn("커넥션 풀 고갈 외 예외 발생: {}", e.toString());
        }
    }

    private boolean isTransientConnectionException(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof SQLTransientConnectionException) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    private void logResult(LoadResult result) {
        // 세마포어 없음 vs 있음을 나란히 비교할 수 있도록 통일된 로그 포맷 사용
        log.info("[{}] success={}, transientConnFail={}, otherFail={}, pendingMax={}, elapsedMs={}",
                result.label(), result.successCount(), result.transientConnFailCount(),
                result.otherFailCount(), result.pendingMax(), result.elapsedMs());
    }

    @FunctionalInterface
    private interface LoadTask {
        void execute(AtomicInteger successCount, AtomicInteger transientConnFailCount,
                     AtomicInteger otherFailCount);
    }

    private record LoadResult(String label, int successCount, int transientConnFailCount,
                               int otherFailCount, int pendingMax, long elapsedMs) {
    }
}
