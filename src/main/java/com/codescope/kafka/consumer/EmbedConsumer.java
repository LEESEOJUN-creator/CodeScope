package com.codescope.kafka.consumer;

import com.codescope.infra.github.GithubApiClient;
import com.codescope.kafka.dto.EmbedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// Day 20 뼈대 - README 수집까지만.
// 임베딩 생성(Ollama)과 pgvector 저장은 Day 25에서 채운다.
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedConsumer {

    private final GithubApiClient githubApiClient;

    @KafkaListener(
            topics = "codescope.embed",
            groupId = "embed-group",
            concurrency = "3"
    )
    public void consume(EmbedMessage message, Acknowledgment ack) {
        // 지금은 README를 가져오는 것까지만 확인. 실패 시(404, Rate Limit 등)
        // 예외를 그대로 던져서 커밋하지 않고 Kafka 재시도/DLT 처리에 맡김
        // (finally로 무조건 ack하지 않는 이유는 CollectConsumer와 동일 원칙)
        try {
            String readme = githubApiClient.fetchReadme(message.fullName());
            log.info("README 수집 완료(임베딩은 Day 25 예정): fullName={}, length={}",
                    message.fullName(), readme.length());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("README 수집 실패: fullName={}", message.fullName(), e);
            throw e;
        }
    }
}
