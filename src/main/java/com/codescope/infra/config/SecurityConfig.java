package com.codescope.infra.config;

import com.codescope.infra.security.GithubOAuth2LoginFailureHandler;
import com.codescope.infra.security.GithubOAuth2LoginSuccessHandler;
import com.codescope.infra.security.JwtAuthenticationEntryPoint;
import com.codescope.infra.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
    private final Environment environment;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // CorsConfig의 CorsConfigurationSource Bean을 그대로 사용한다
                // (프론트엔드 localhost:3000 → 백엔드 credentials 포함 요청 허용)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/api-docs/**").permitAll()
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
                        .requestMatchers(HttpMethod.GET, "/api/trends/**").permitAll();

                    // 부하테스트 엔드포인트는 인증 없이 호출 가능해야 JMeter/k6가
                    // 직접 두드릴 수 있다. SecurityConfig는 프로파일과 무관하게
                    // 항상 로드되므로, permitAll 규칙 자체도 test 프로파일일 때만
                    // 추가해 운영 환경엔 이 규칙의 흔적조차 남기지 않는다
                    // (컨트롤러도 @Profile("test")라 빈 부재 + 규칙 부재 이중 방어).
                    if (environment.acceptsProfiles(Profiles.of("test"))) {
                        auth.requestMatchers(HttpMethod.POST, "/api/test/**").permitAll();
                    }

                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(githubOAuth2LoginSuccessHandler)
                        .failureHandler(githubOAuth2LoginFailureHandler)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
