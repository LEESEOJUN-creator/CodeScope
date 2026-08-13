package com.codescope.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConsumerConfig {

    // 제네릭을 <String, Object>로 둔 이유:
    //   이전에는 <String, String>이었는데, 실제로 흐르는 값 타입은
    //   CollectMessage / EmbedMessage다. 런타임에는 타입 소거로 동작했지만
    //   코드를 읽는 사람에게 "값이 String"이라는 잘못된 신호를 준다.
    //   그렇다고 타입별로 팩토리를 나누면, JsonDeserializer가 __TypeId__ 헤더로
    //   이미 타입을 정확히 구분하고 있어 얻는 게 없이 설정만 두 배가 된다.
    //   (게다가 @RetryableTopic이 자동 생성하는 retry/DLT 리스너까지 각각
    //    어떤 팩토리를 쓸지 지정해야 해 복잡도가 더 커진다)
    //   그래서 팩토리는 하나로 두되, 값 타입만 Object로 정직하게 표기한다.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
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
