package com.codescope.infra.config;

import org.springframework.beans.factory.annotation.Value;
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
            ConsumerFactory<String, Object> consumerFactory,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 왜 필요한가: enable-auto-commit: false로 자동 커밋을 껐어도,
        // 컨테이너의 AckMode를 명시하지 않으면 기본값(BATCH)이 적용돼
        // @KafkaListener 메서드에서 Acknowledgment를 받아 호출해도 수동 제어가 안 됨.
        // MANUAL: ack.acknowledge() 호출 시점에 커밋 요청을 큐에 쌓고,
        //         다음 poll 전에 실제 커밋 (성능과 제어를 절충)
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // 왜 auto-startup을 직접 읽는가: 이 팩토리는 Boot 자동 설정(configurer)을
        //   거치지 않고 직접 만들어서, spring.kafka.listener.auto-startup이 저절로
        //   적용되지 않는다. 표준 속성명을 그대로 받아 연결한다.
        // 왜 필요한가: @SpringBootTest 컨텍스트가 뜨면 리스너가 개발용 Kafka(9094)에
        //   실제 앱과 "같은 그룹 ID"로 붙어 파티션을 나눠 가졌다. 테스트 중 개발 앱의
        //   임베딩 메시지를 가로채 처리할 수 있었고(일부 테스트는 임베딩을 mock으로
        //   바꿔 가짜 벡터를 저장할 수 있음), DLT도 다시 읽었다. Gradle 테스트
        //   태스크가 이 값을 false로 넘긴다(build.gradle). retry/DLT 리스너도 이
        //   팩토리로 만들어지므로 함께 꺼진다.
        factory.setAutoStartup(autoStartup);
        return factory;
    }
}
