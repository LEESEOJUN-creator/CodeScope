package com.codescope.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 왜 필요한가: enable-auto-commit: false로 자동 커밋을 껐어도,
        // 컨테이너의 AckMode를 명시하지 않으면 기본값(BATCH)이 적용돼
        // @KafkaListener 메서드에서 Acknowledgment를 받아 호출해도 수동 제어가 안 됨.
        // MANUAL: ack.acknowledge() 호출 시점에 커밋 요청을 큐에 쌓고,
        //         다음 poll 전에 실제 커밋 (성능과 제어를 절충)
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
