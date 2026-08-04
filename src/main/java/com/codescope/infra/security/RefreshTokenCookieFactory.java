package com.codescope.infra.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RefreshTokenCookieFactory {

    private static final String COOKIE_NAME = "refreshToken";

    private final long refreshExpiration;
    private final boolean cookieSecure;

    public RefreshTokenCookieFactory(
            @Value("${jwt.refresh-expiration}") long refreshExpiration,
            @Value("${jwt.cookie-secure:false}") boolean cookieSecure
    ) {
        this.refreshExpiration = refreshExpiration;
        this.cookieSecure = cookieSecure;
    }

    // 로컬 http는 false, HTTPS 배포 시 환경변수로 true 주입
    public ResponseCookie create(String refreshToken) {
        return ResponseCookie.from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMillis(refreshExpiration))
                .build();
    }

    public ResponseCookie expire() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }
}
