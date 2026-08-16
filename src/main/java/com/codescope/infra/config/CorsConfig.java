package com.codescope.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 프론트엔드(Day 30~31, Next.js App Router)가 별도 오리진(localhost:3000)에서
 * 백엔드(localhost:8080)로 쿠키 포함 요청(credentials: 'include')을 보내려면
 * CORS 허용 + allowCredentials(true)가 둘 다 필요하다. 쿠키(Refresh Token)를
 * 쓰는 이상 브라우저가 이 조합을 요구한다 — allowCredentials(true)와
 * allowedOrigins("*")는 스펙상 동시에 쓸 수 없어(브라우저가 거부) origin을
 * 명시적으로 나열해야 한다.
 *
 * 왜 origin을 하드코딩하지 않고 application.yml(cors.allowed-origins)로 뺐는가:
 * CLAUDE.md 컨벤션(임계치/설정값은 yml로만 관리) + Day 33~34 배포 시 운영
 * 도메인으로 값만 바꿔치기 가능해야 하기 때문 — 코드 재컴파일 없이 환경별로
 * 다른 오리진을 허용할 수 있어야 한다.
 *
 * 왜 SecurityConfig가 아니라 별도 클래스인가: SecurityFilterChain 설정과
 * CORS 정책은 관심사가 다르다(SecurityConfig는 .cors(Customizer.withDefaults())로
 * 이 Bean을 자동 인식만 하면 됨 — Spring Security가 CorsConfigurationSource
 * 타입 Bean을 컨텍스트에서 찾아 그대로 사용한다).
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins}") List<String> allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // 쿠키(Refresh Token)를 주고받아야 하므로 반드시 true.
        // allowedOrigins에 "*"를 쓸 수 없는 이유가 바로 이 옵션과의 스펙 제약 때문.
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
