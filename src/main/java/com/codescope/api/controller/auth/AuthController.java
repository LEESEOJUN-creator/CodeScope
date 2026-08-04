package com.codescope.api.controller.auth;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.user.service.RefreshTokenService;
import com.codescope.infra.security.JwtProvider;
import com.codescope.infra.security.RefreshTokenCookieFactory;
import com.codescope.infra.security.TokenPair;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Auth", description = "JWT 발급/갱신/로그아웃")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RefreshTokenService refreshTokenService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    @Operation(summary = "Access Token 재발급 (Refresh Token Rotation)")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<?>> refresh(
            @CookieValue(value = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response
    ) {
        if (refreshToken == null || !jwtProvider.validateToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Refresh Token이 유효하지 않습니다. 다시 로그인해주세요."));
        }

        Long userId = jwtProvider.getUserId(refreshToken);
        TokenPair tokenPair = refreshTokenService.validateAndRotate(userId, refreshToken);

        ResponseCookie cookie = refreshTokenCookieFactory.create(tokenPair.refreshToken());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.ok(ApiResponse.success(Map.of("accessToken", tokenPair.accessToken())));
    }

    @Operation(summary = "로그아웃 (Refresh Token 무효화)")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(value = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response
    ) {
        if (refreshToken != null && jwtProvider.validateToken(refreshToken)) {
            Long userId = jwtProvider.getUserId(refreshToken);
            refreshTokenService.delete(userId);
        }

        ResponseCookie expiredCookie = refreshTokenCookieFactory.expire();
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie.toString());

        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
