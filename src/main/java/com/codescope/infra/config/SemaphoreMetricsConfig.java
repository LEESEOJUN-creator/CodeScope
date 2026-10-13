package com.codescope.infra.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 모든 세마포어 Bean(dbSemaphore/ollamaSemaphore/claudeSemaphore)의 상태를
 * Prometheus 게이지로 노출한다.
 *
 * 왜 필요한가: 지금까지 배압 실험(docs/performance/day35_backpressure.md)은
 *   결과(응답시간·처리량)만 보고 "세마포어가 실제로 막고 있었는가"는 로그로
 *   추정했다. 남은 permits와 대기 스레드 수를 시계열로 보면, 응답시간이
 *   튀는 순간이 세마포어 대기 때문인지 하류(DB/API) 자체 지연 때문인지
 *   Grafana에서 바로 구분된다.
 *
 * 왜 Map<String, Semaphore> 주입인가: 세마포어가 추가돼도(예: GitHub API용)
 *   이 클래스를 고치지 않고 Bean 이름이 그대로 name 태그가 된다.
 */
@Configuration
public class SemaphoreMetricsConfig {

    @Bean
    public MeterBinder semaphoreMetrics(Map<String, Semaphore> semaphores) {
        return registry -> semaphores.forEach((name, semaphore) -> {
            Gauge.builder("codescope.semaphore.available", semaphore, Semaphore::availablePermits)
                    .tag("name", name)
                    .description("세마포어 남은 permits")
                    .register(registry);
            // getQueueLength()는 Javadoc상 추정치지만, 경향(대기열이 쌓이는가)을
            // 보는 용도로는 충분하다.
            Gauge.builder("codescope.semaphore.queued", semaphore, Semaphore::getQueueLength)
                    .tag("name", name)
                    .description("세마포어 획득 대기 중인 스레드 수(추정치)")
                    .register(registry);
        });
    }
}
