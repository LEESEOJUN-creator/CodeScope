package com.codescope.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @SpringBootTest 컨텍스트에서 Kafka 리스너(retry/DLT 포함)가 하나도 실행되지
 * 않는지 검증한다 — 테스트가 개발용 Kafka의 실제 컨슈머 그룹에 붙어 개발 앱의
 * 메시지를 가로채던 문제의 회귀 테스트(KafkaConsumerConfig, build.gradle 참고).
 */
@SpringBootTest
class KafkaListenerTestIsolationTest {

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Test
    @DisplayName("테스트 컨텍스트의 Kafka 리스너 컨테이너는 등록만 되고 시작되지 않는다")
    void listenersAreNotRunningInTests() {
        assertThat(registry.getAllListenerContainers())
                .as("리스너 컨테이너가 등록은 돼 있어야 검증이 의미 있다")
                .isNotEmpty()
                .noneMatch(MessageListenerContainer::isRunning);
    }
}
