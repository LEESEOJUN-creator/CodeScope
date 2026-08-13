package com.codescope.domain.repo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 수집 파이프라인의 중복 처리 방지.
 *
 * 이전 설계는 "처리 중"과 "처리 완료"를 단일 키(TTL 10분)로 겸했다.
 * 그 결과 처리가 실패해도 락이 10분간 남아, Kafka가 재전달한 메시지가
 * tryLock()에서 막히고 그대로 ack되어 메시지가 유실됐다(코드리뷰 A).
 * 또 완료 표식과 락이 같아 "이미 저장된 레포의 갱신"조차 막혔다(코드리뷰 C).
 *
 * 그래서 두 개념을 키/TTL 모두 분리했다.
 *   - processing: 지금 누군가 처리 중 → 짧게 잡아 실패 시 빨리 풀림
 *   - completed : 최근에 성공적으로 처리 완료 → 다음 수집 주기 전까지만 유지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateCheckService {

    private static final String PROCESSING_KEY_PREFIX = "lock:processing:";
    private static final String COMPLETED_KEY_PREFIX = "lock:completed:";

    // 처리 중 락은 1분으로 짧게 유지한다.
    // 왜: 처리에 실패하면 이 락이 풀려야 재전달된 메시지가 다시 시도할 수 있다.
    //     이전처럼 10분이면 재시도 기회가 사실상 사라져 메시지가 유실된다.
    //     레포 1건 저장은 수백 ms 수준이라 1분이면 정상 처리에는 충분히 넉넉하다.
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(1);

    // 완료 표식은 9분으로, 수집 주기(10분)보다 "짧게" 잡는다.
    // 왜: 이전에는 TTL(10분)과 스케줄러 주기(10분)가 같아, 다음 사이클이
    //     도착하는 시점과 만료 시점이 겹쳐 어떤 사이클은 중복 처리되고
    //     어떤 사이클은 통째로 스킵되는 경계 오작동이 있었다(코드리뷰 H).
    //     9분으로 두면 다음 사이클(10분 후)에는 항상 만료되어 있어
    //     "주기마다 정확히 한 번 갱신"이 결정적으로 보장된다.
    private static final Duration COMPLETED_TTL = Duration.ofMinutes(9);

    private final StringRedisTemplate redisTemplate;

    /**
     * 처리 권한을 원자적으로 획득한다(SET NX).
     * 확인과 점유가 한 명령으로 처리되어 TOCTOU race condition이 발생하지 않는다.
     */
    public boolean tryLock(String identifier) {
        String key = PROCESSING_KEY_PREFIX + identifier;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, "processing", PROCESSING_TTL);

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("identifier={} 는 다른 처리자가 점유 중", identifier);
            return false;
        }
        return true;
    }

    /**
     * 처리에 성공했을 때만 호출한다.
     * 실패 시에는 호출하지 않아야 다음 주기/재전달에서 다시 처리될 수 있다.
     */
    public void markCompleted(String identifier) {
        String key = COMPLETED_KEY_PREFIX + identifier;
        redisTemplate.opsForValue().set(key, "completed", COMPLETED_TTL);
    }

    /**
     * 최근(9분 이내)에 처리 완료됐는지 확인한다.
     * 같은 수집 주기 안에서 같은 레포가 중복 유입될 때 DB 접근 자체를 막는 용도.
     */
    public boolean isRecentlyCompleted(String identifier) {
        String key = COMPLETED_KEY_PREFIX + identifier;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
