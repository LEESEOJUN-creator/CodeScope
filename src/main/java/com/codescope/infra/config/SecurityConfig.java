package com.codescope.infra.config;

import com.codescope.infra.security.GithubOAuth2LoginFailureHandler;
import com.codescope.infra.security.GithubOAuth2LoginSuccessHandler;
import com.codescope.infra.security.JwtAuthenticationEntryPoint;
import com.codescope.infra.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
