package com.codescope.kafka.consumer;

import com.codescope.infra.github.GithubApiClient;
import com.codescope.kafka.dto.EmbedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

// Day 20 뼈대 - README 수집까지만.
// 임베딩 생성(Ollama)과 pgvector 저장은 Day 25에서 채운다.
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedConsumer {

    private final GithubApiClient githubApiClient;

    // exclude에 404를 넣은 것이 이 컨슈머에서 특히 중요하다.
    // 왜: README가 없는 레포는 GitHub이 영구적으로 404를 반환한다.
    //   이전에는 기본 에러 핸들러가 이런 요청을 백오프 없이 10회 반복한 뒤
    //   조용히 버렸다 — 성공할 수 없는 호출로 Rate Limit만 소모하고
    //   기록도 남지 않았다(코드리뷰 B).
    //   이제 404/401/403은 재시도 없이 곧바로 DLT로 보내 기록을 남긴다.
    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            exclude = {
                    HttpClientErrorException.NotFound.class,
                    HttpClientErrorException.Unauthorized.class,
                    HttpClientErrorException.Forbidden.class
            },
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
    )
    @KafkaListener(
            topics = "codescope.embed",
            groupId = "embed-group",
            concurrency = "3"
    )
    public void consume(EmbedMessage message, Acknowledgment ack) {
        // 실패 시 예외를 그대로 던져 커밋하지 않는다.
        // @RetryableTopic이 일시적 오류는 지수 백오프로 재시도하고,
        // 재시도해도 소용없는 4xx는 즉시 DLT로 보낸다.
        String readme = githubApiClient.fetchReadme(message.fullName());

        log.info("README 수집 완료(임베딩은 Day 25 예정): fullName={}, length={}",
                message.fullName(), readme == null ? 0 : readme.length());

        ack.acknowledge();
    }

    @DltHandler
    public void handleDlt(EmbedMessage message,
                          @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage) {
        log.error("[DLT] README 수집 최종 실패: fullName={}, reason={}",
                message.fullName(), errorMessage);
    }
}
