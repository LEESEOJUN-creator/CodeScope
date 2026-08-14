package com.codescope.client.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// Ollama 임베딩 API 호출용 RestClient 등록.
// 왜 RestClient인가: infra.github.RestClientConfig(Day 19)와 동일한 이유 —
//   가상 스레드 환경에서는 블로킹 I/O가 캐리어 스레드를 점유하지 않고
//   그대로 대기하므로, WebClient(Reactive)의 비동기 이점이 없이
//   복잡도만 늘어난다. 동기 API인 RestClient로 충분.
@Configuration
public class OllamaRestClientConfig {

    @Bean
    public RestClient ollamaRestClient(@Value("${llm.ollama.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
