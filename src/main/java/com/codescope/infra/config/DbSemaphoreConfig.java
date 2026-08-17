package com.codescope.infra.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;

/**
 * DB 커넥션 배압(backpressure) 제어용 세마포어.
 *
 * 왜 필요한가:
 *   가상 스레드는 수백~수천 개가 동시에 뜰 수 있는데, HikariCP maximum-pool-size는
 *   유한(현재 10)하다. 세마포어 없이 그대로 두면 풀 크기를 넘어선 스레드는
 *   HikariCP 대기열에서 connection-timeout(30s) 후 SQLTransientConnectionException으로
 *   "실패"한다. 세마포어로 커넥션을 요구하는 스레드 수 자체를 풀 크기 이하로
 *   미리 제한하면, 넘치는 요청은 예외 대신 "세마포어 대기(지연)"로 바뀐다.
 *
 * 사용 방법 (필수, 아래 순서를 반드시 지킬 것):
 *   1. acquire()는 try 블록 "바깥"에서 호출한다.
 *      → try 안에서 acquire하면, acquire 자체가 실패(InterruptedException)해도
 *        finally의 release()가 실행되어 permits 카운트가 실제 획득 없이 늘어난다.
 *   2. release()는 반드시 finally에서 보장한다.
 *   3. acquire() 중 InterruptedException 발생 시 Thread.currentThread().interrupt()로
 *      인터럽트 상태를 복원한다 (삼켜버리면 상위 코드가 인터럽트를 알 수 없음).
 *   4. @Transactional 메서드 "안"에서 acquire하지 않는다.
 *      → @Transactional은 AOP 프록시라 메서드 본문에 진입한 시점엔 이미
 *        트랜잭션이 시작되어 커넥션이 물려 있다. acquire를 트랜잭션 시작
 *        "이전"에 호출해, 세마포어 구간이 트랜잭션 구간을 완전히 감싸야 한다.
 *
 *   예시:
 *     dbSemaphore.acquire();
 *     try {
 *         someTransactionalService.doWork(); // 여기서 @Transactional 시작
 *     } finally {
 *         dbSemaphore.release();
 *     }
 *
 * 이번 범위에는 포함하지 않음: AOP/어노테이션 기반 자동 적용.
 *   수동 try-finally 적용이 이 프로젝트의 확정된 설계 결정 (가시성 우선).
 *   실제 사용처(Consumer, Service) 적용은 이후 별도 작업에서 진행.
 */
@Slf4j
@Configuration
public class DbSemaphoreConfig {

    @Bean
    public Semaphore dbSemaphore(
            DataSource dataSource,
            // reserve: 세마포어를 거치지 않고 커넥션을 쓰는 경로(사용자 HTTP 요청의
            // @Transactional Service, @Scheduled 스케줄러, Actuator health의 DB
            // indicator)를 위한 예약분. 배치가 풀을 독점해 사용자 조회가 굶는 것을 방지.
            // 기본값 2 (2026-08-17 실험 A/B/C 원복 완료 — 실험 C에서 reserve=0으로
            // 임시 변경했다가 실험 종료 후 원래 값으로 되돌림. docs/performance/
            // day35_backpressure.md 참고: 이번 실험 조건(count=50, durationMs=8000)에서는
            // reserve 유무의 효과가 뚜렷하게 관찰되지 않았고, 세마포어 자체의 존재 여부가
            // 더 지배적인 요인이었다 — reserve 세부 튜닝은 추가 실험 과제로 남김).
            @Value("${codescope.db-semaphore.reserve:2}") int reserve
    ) throws SQLException {
        int maximumPoolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        int permits = maximumPoolSize - reserve;

        // pool size보다 reserve가 큰 설정 실수를 조용히 넘기지 않고 기동 자체를 막는다.
        if (permits < 1) {
            throw new IllegalStateException(
                    "DB 세마포어 permits가 1 미만입니다 (maximumPoolSize=%d, reserve=%d, permits=%d). "
                            .formatted(maximumPoolSize, reserve, permits)
                            + "codescope.db-semaphore.reserve 값을 pool size보다 작게 조정하세요."
            );
        }

        log.info("DB 세마포어 초기화: maximumPoolSize={}, reserve={}, permits={}",
                maximumPoolSize, reserve, permits);

        // fair 모드(true): 비공정 모드는 barging(늦게 온 스레드가 대기자를 제치고
        // 획득)이 발생해 일부 요청의 대기 시간이 예측 불가능하게 길어질 수 있다.
        // 배압 제어의 목적이 "균일한 처리"이므로, fair 모드가 갖는 처리량 손해보다
        // 대기 시간의 예측 가능성을 우선한다.
        return new Semaphore(permits, true);
    }
}
