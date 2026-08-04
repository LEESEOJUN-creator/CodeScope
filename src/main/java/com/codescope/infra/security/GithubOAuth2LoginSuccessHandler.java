package com.codescope.infra.security;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.user.entity.User;
import com.codescope.domain.user.service.RefreshTokenService;
import com.codescope.domain.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

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
    private final ObjectMapper objectMapper;

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
        // TODO: ApiResponse에 errorCode 필드 추가 후 "EMAIL_NOT_FOUND" 등으로 세분화 필요
        //  현재는 message 문자열만으로 실패 사유를 전달함 (프론트엔드 연동 시 재검토)
        if (email == null) {
            log.warn("[GithubOAuth2] githubId={}의 email이 null이라 로그인을 처리할 수 없습니다.", githubId);
            writeErrorResponse(response, "GitHub 계정에서 이메일 정보를 가져올 수 없습니다.");
            return;
        }

        User user = userService.findOrCreateByGithub(githubId, email, username, profileImageUrl);
        Long userId = user.getUserId();

        String accessToken = jwtProvider.createAccessToken(userId);
        String refreshToken = jwtProvider.createRefreshToken(userId);
        refreshTokenService.save(userId, refreshToken);

        ResponseCookie cookie = refreshTokenCookieFactory.create(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // TODO: 프론트엔드가 아직 없어 현재는 검증 목적으로 JSON을 직접 응답 바디에 씀
        //  프론트 연동 시 sendRedirect로 변경 예정 (프론트 URL로 리다이렉트하며 토큰 전달)
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.success(Map.of("accessToken", accessToken)))
        );
    }

    private void writeErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(message)));
    }
}
