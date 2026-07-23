package com.codescope.domain.repo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateCheckService {

    private static final String LOCK_KEY_PREFIX = "lock:repo:";
    private static final Duration LOCK_TIMEOUT = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(String identifier) {
        String key = LOCK_KEY_PREFIX + identifier;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "processing", LOCK_TIMEOUT);

        if (!Boolean.TRUE.equals(acquired)) {
            log.info("identifier={}는 이미 처리 중이라 이번 수집을 스킵합니다.", identifier);
            return false;
        }
        return true;
    }

    public void releaseLock(String identifier) {
        String key = LOCK_KEY_PREFIX + identifier;
        redisTemplate.delete(key);
    }
}
