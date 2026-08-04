package com.codescope.domain.user.service;

import com.codescope.common.exception.RefreshTokenReuseException;
import com.codescope.infra.security.JwtProvider;
import com.codescope.infra.security.TokenPair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class RefreshTokenService {

    // key가 refresh:user:{userId} 하나뿐이라 유저당 Refresh Token 1개(1세션)만 유지되는 구조
    // TODO: 멀티 디바이스 지원이 필요하면 key에 deviceId를 추가하는 방식으로 확장 가능
    private static final String REFRESH_KEY_PREFIX = "refresh:user:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProvider jwtProvider;
    private final long refreshExpiration;

    public RefreshTokenService(
            StringRedisTemplate redisTemplate,
            JwtProvider jwtProvider,
            @Value("${jwt.refresh-expiration}") long refreshExpiration
    ) {
        this.redisTemplate = redisTemplate;
        this.jwtProvider = jwtProvider;
        this.refreshExpiration = refreshExpiration;
    }

    public void save(Long userId, String refreshToken) {
        String key = REFRESH_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, refreshToken, Duration.ofMillis(refreshExpiration));
    }

    public TokenPair validateAndRotate(Long userId, String refreshToken) {
        String key = REFRESH_KEY_PREFIX + userId;
        String savedToken = redisTemplate.opsForValue().getAndDelete(key);

        if (savedToken == null || !savedToken.equals(refreshToken)) {
            log.warn("[RefreshToken] userId={}의 Refresh Token 재사용이 의심되어 무효화합니다.", userId);
            throw new RefreshTokenReuseException("Refresh Token이 유효하지 않거나 재사용이 감지되었습니다.");
        }

        String newAccessToken = jwtProvider.createAccessToken(userId);
        String newRefreshToken = jwtProvider.createRefreshToken(userId);
        save(userId, newRefreshToken);

        return TokenPair.of(newAccessToken, newRefreshToken);
    }

    public void delete(Long userId) {
        redisTemplate.delete(REFRESH_KEY_PREFIX + userId);
    }
}
