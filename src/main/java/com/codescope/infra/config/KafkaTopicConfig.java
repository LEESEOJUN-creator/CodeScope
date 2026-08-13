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

    // codescope.dlt 토픽 정의를 제거한 이유:
    //   @RetryableTopic은 원본 토픽 이름을 기준으로 재시도/DLT 토픽을 자동
    //   생성한다(codescope.collect-retry-0, codescope.collect-dlt 등).
    //   수동으로 만든 단일 codescope.dlt는 이 명명 규칙과 달라 실제로는
    //   어디에도 연결되지 않는 죽은 토픽이었다.
    //   토픽별로 DLT가 나뉘면 "어느 단계에서 실패했는지"가 토픽 이름만으로
    //   구분되어 오히려 조사에 유리하므로, @RetryableTopic 방식으로 통일한다.
}
