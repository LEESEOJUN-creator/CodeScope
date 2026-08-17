package com.codescope.infra.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

// GitHub API 호출용 RestClient 등록
// 왜 RestClient인가: 가상 스레드 환경에서는 블로킹 I/O가 캐리어 스레드를
//   점유하지 않고 그대로 대기하므로, WebClient(Reactive) 스타일의
//   비동기 논블로킹 처리가 별도 이점 없이 학습/코드 복잡도만 늘어남
//   → 동기 API인 RestClient(Spring 6.1+)로 충분
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient githubRestClient(
            @Value("${github.api.base-url}") String baseUrl,
            @Value("${github.token}") String githubToken,
            @Value("${github.api.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${github.api.read-timeout-ms}") long readTimeoutMs
    ) {
        // 왜 타임아웃을 명시하는가: 기본 RestClient는 connect/read
        // 타임아웃이 무제한이라, GitHub 쪽 응답이 지연되면 이 요청을
        // 부른 스레드가 무한정 대기한다(EmbedConsumer 워커 스레드가
        // 정확히 이 상태로 멈춰있는 것을 실측으로 확인). 값 근거는
        // application.yaml 주석 참고.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + githubToken)
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
    }
}
