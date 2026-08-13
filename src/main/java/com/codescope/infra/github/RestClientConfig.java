package com.codescope.infra.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

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
            @Value("${github.token}") String githubToken
    ) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + githubToken)
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
    }
}
