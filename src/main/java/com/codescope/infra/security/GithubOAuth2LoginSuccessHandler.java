package com.codescope.infra.security;

import com.codescope.domain.user.entity.User;
import com.codescope.domain.user.service.RefreshTokenService;
import com.codescope.domain.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubOAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    @Value("${frontend.base-url}")
    private String frontendBaseUrl;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        OAuth2AuthenticationToken oAuth2Token = (OAuth2AuthenticationToken) authentication;
        OAuth2User oAuth2User = oAuth2Token.getPrincipal();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        Long githubId = ((Number) attributes.get("id")).longValue();
        String username = (String) attributes.get("login");
        String email = (String) attributes.get("email");
        String profileImageUrl = (String) attributes.get("avatar_url");

        // TODO: GitHub 계정이 이메일을 비공개로 설정한 경우 attributes에 email이 안 내려옴
        //  추후 GitHub API의 /user/emails 엔드포인트로 별도 조회해 보완 필요
        //
        // 프론트가 이 실패를 인지할 수 있도록 JSON 대신 /login?error=...로 리다이렉트한다.
        // error 값은 프론트 /login 페이지가 그대로 읽어 안내 문구로 매핑한다.
        if (email == null) {
            log.warn("[GithubOAuth2] githubId={}의 email이 null이라 로그인을 처리할 수 없습니다.", githubId);
            response.sendRedirect(frontendBaseUrl + "/login?error=email_missing");
            return;
        }

        User user = userService.findOrCreateByGithub(githubId, email, username, profileImageUrl);
        Long userId = user.getUserId();

        // 왜 Access Token을 응답에 안 싣는가: sendRedirect의 목적지 URL은
        // 브라우저 주소창·서버 접근 로그·Referer 헤더 등 여러 경로로 노출될 수 있어,
        // 쿼리 파라미터로 Access Token을 실어 보내면 사실상 토큰이 유출되는 것과 같다.
        // Refresh Token만 HttpOnly 쿠키로 세팅해 두면, 프론트 콜백 페이지가 그 쿠키로
        // POST /api/auth/refresh를 호출해 Access Token을 응답 바디로만 받아갈 수 있다
        // (바디는 URL과 달리 로그에 남지 않음).
        String refreshToken = jwtProvider.createRefreshToken(userId);
        refreshTokenService.save(userId, refreshToken);

        ResponseCookie cookie = refreshTokenCookieFactory.create(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        response.sendRedirect(
                UriComponentsBuilder.fromUriString(frontendBaseUrl).path("/callback").toUriString());
    }
}
