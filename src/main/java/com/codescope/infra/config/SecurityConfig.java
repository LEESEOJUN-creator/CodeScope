package com.codescope.infra.config;

import com.codescope.infra.security.GithubOAuth2LoginFailureHandler;
import com.codescope.infra.security.GithubOAuth2LoginSuccessHandler;
import com.codescope.infra.security.JwtAuthenticationEntryPoint;
import com.codescope.infra.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final GithubOAuth2LoginSuccessHandler githubOAuth2LoginSuccessHandler;
    private final GithubOAuth2LoginFailureHandler githubOAuth2LoginFailureHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // CorsConfig의 CorsConfigurationSource Bean을 그대로 사용한다
                // (Day 30~31: 프론트엔드 localhost:3000 → 백엔드 credentials 포함 요청 허용)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/api-docs/**").permitAll()
                        // K8s liveness/readiness probe는 인증 수단 없이 호출되므로
                        // 인증을 요구하면 401을 받고 "실패"로 판정됨
                        // → kubelet이 컨테이너를 계속 죽여 CrashLoopBackOff 발생(Exit Code 143)
                        // 정보 노출 위험은 낮음: management.endpoint.health.show-details 기본값이
                        // never라 {"status":"UP"} 외 상세 정보는 응답에 포함되지 않음
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/repos/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/repos/**").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/repos/**").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/repos/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/repos/**").authenticated()
                        // CLAUDE.md API 명세: /api/recommend는 비로그인도 호출 가능
                        // (skills 파라미터로 스택 직접 지정). 로그인 시 저장된 스택
                        // 자동 사용은 User 서비스 로직 구현 시점 과제(현재 미구현)
                        .requestMatchers(HttpMethod.GET, "/api/recommend").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/trends/**").permitAll()
                        // Day 35: 부하 테스트 트리거용 — LoadTestController 자체가
                        // @Profile("test")라 운영 프로파일에서는 빈 자체가 안 뜬다.
                        // JMeter/k6가 JWT 없이 바로 호출할 수 있어야 시나리오가 단순해진다.
                        .requestMatchers(HttpMethod.POST, "/api/test/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(githubOAuth2LoginSuccessHandler)
                        .failureHandler(githubOAuth2LoginFailureHandler)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
