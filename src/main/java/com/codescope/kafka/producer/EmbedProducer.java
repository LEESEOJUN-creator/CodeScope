package com.codescope.kafka.producer;

import com.codescope.kafka.dto.EmbedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedProducer {

    private static final String TOPIC = "codescope.embed";

    private final KafkaTemplate<String, EmbedMessage> kafkaTemplate;

    // key도 fullName으로 보냄
    // 왜: 같은 레포에 대한 임베딩 이벤트가 항상 같은 파티션으로 가야 순서가 보장됨
    public CompletableFuture<SendResult<String, EmbedMessage>> publish(String fullName) {
        return kafkaTemplate.send(TOPIC, fullName, new EmbedMessage(fullName))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 발행 실패: fullName={}", fullName, ex);
                    } else {
                        log.debug("Kafka 발행 성공: fullName={}, partition={}, offset={}",
                                fullName,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
