package com.codescope.client.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

// Ollama 임베딩 API 호출용 RestClient 등록.
// 왜 RestClient인가: infra.github.RestClientConfig와 동일한 이유 —
//   가상 스레드 환경에서는 블로킹 I/O가 캐리어 스레드를 점유하지 않고
//   그대로 대기하므로, WebClient(Reactive)의 비동기 이점이 없이
//   복잡도만 늘어난다. 동기 API인 RestClient로 충분.
@Configuration
public class OllamaRestClientConfig {

    @Bean
    public RestClient ollamaRestClient(
            @Value("${llm.ollama.base-url}") String baseUrl,
            @Value("${llm.ollama.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${llm.ollama.read-timeout-ms}") long readTimeoutMs
    ) {
        // 왜 타임아웃을 명시하는가: RestClientConfig(GitHub)와 동일한
        // 이유. 값 근거(read 20초 = 청크당 실측 3.5초의 5배 이상 여유)는
        // application.yaml 주석 참고.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    // 생성(OllamaLlmClient) 전용 RestClient. 임베딩용 ollamaRestClient와
    // baseUrl은 같지만 read-timeout을 별도로 둔다(생성이 임베딩보다 오래
    // 걸릴 수 있음, application.yaml llm.ollama.generation 주석 참고) —
    // 그래서 하나로 합치지 않고 Bean을 분리했다.
    @Bean
    public RestClient ollamaGenerationRestClient(
            @Value("${llm.ollama.base-url}") String baseUrl,
            @Value("${llm.ollama.generation.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${llm.ollama.generation.read-timeout-ms}") long readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
