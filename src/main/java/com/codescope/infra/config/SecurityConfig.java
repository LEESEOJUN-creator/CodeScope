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
