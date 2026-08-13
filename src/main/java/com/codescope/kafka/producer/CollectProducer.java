package com.codescope.kafka.producer;

import com.codescope.kafka.dto.CollectMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectProducer {

    private static final String TOPIC = "codescope.collect";

    private final KafkaTemplate<String, CollectMessage> kafkaTemplate;

    // key는 message.fullName()으로 보냄
    // 왜: 같은 레포에 대한 메시지가 항상 같은 파티션으로 가야 순서가 보장되는데,
    //     Kafka는 key를 해시해서 파티션을 정하므로 key를 repoFullName으로 고정
    // 왜 CompletableFuture를 그대로 반환하는가: CollectScheduler가 발행 성공/실패를
    //   즉시 확인해서(get(timeout)) 실패분만 재시도해야 하므로, 여기서 미리
    //   whenComplete로 소비해버리면 호출부에서 결과를 알 방법이 없어짐
    public CompletableFuture<SendResult<String, CollectMessage>> publish(CollectMessage message) {
        return kafkaTemplate.send(TOPIC, message.fullName(), message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 발행 실패: repoFullName={}", message.fullName(), ex);
                    } else {
                        log.debug("Kafka 발행 성공: repoFullName={}, partition={}, offset={}",
                                message.fullName(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
