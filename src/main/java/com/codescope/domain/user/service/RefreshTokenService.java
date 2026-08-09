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
            // 예외를 먼저 던지면 아래 호출이 실행되지 않으므로 반드시 이 순서를 지킨다.
            revokeAllByUserId(userId);
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

    // Refresh Token Rotation에서 재사용이 감지되면 탈취자가 훔친 토큰인지,
    // 정상 사용자가 이전 토큰을 다시 보낸 것인지 서버는 구분할 수 없다.
    // (OAuth 2.0 Security BCP 권고) 구분이 불가능하므로 어느 한쪽만 끊는 대신
    // 해당 userId에 발급된 Refresh Token을 전부 무효화해 둘 다 재로그인시킨다.
    //
    // 현재 키 구조(REFRESH_KEY_PREFIX + userId)는 세션당 1개만 발급하므로
    // 실질적으로 delete()와 동일하게 키 1개만 지워진다. 그럼에도 별도 메서드로
    // 분리하는 이유는 "1세션 구조라 우연히 안전한 것"과 "재사용 시 전체 무효화가
    // 설계 의도임"을 코드로 구분해 남기기 위함이다. 멀티 디바이스를 지원해
    // userId당 키가 여러 개로 늘어나도(예: deviceId 추가) 이 메서드 호출부는
    // 그대로 두고 내부 구현만 바꾸면 되도록 격리해 둔다.
    private void revokeAllByUserId(Long userId) {
        redisTemplate.delete(REFRESH_KEY_PREFIX + userId);
    }
}
