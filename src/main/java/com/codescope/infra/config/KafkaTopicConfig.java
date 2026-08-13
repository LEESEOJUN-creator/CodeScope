package com.codescope.infra.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic collectTopic() {
        // 병렬 처리량 확보 목적: GitHub 레포 수집 이벤트를 여러 파티션에
        // 분산시켜 Consumer 인스턴스를 파티션 수만큼 수평 확장 가능
        return TopicBuilder.name("codescope.collect")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic embedTopic() {
        // 병렬 처리량 확보 목적: 레포 1개당 임베딩 생성이 약 3초 걸려
        // 파티션을 나눠 여러 Consumer가 동시에 처리하지 않으면 병목 발생
        return TopicBuilder.name("codescope.embed")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic dltTopic() {
        // 파티션 1개(전체 순서 보장)로 의도적으로 다르게 설정:
        // 실패 사례는 병렬 처리량보다 "발생 순서대로 조사"가 더 중요하므로
        // 파티션을 나누지 않아 메시지 순서가 뒤섞이지 않도록 함
        return TopicBuilder.name("codescope.dlt")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
