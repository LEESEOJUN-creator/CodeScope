package com.codescope.kafka.consumer;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.service.DuplicateCheckService;
import com.codescope.kafka.dto.CollectMessage;
import com.codescope.kafka.producer.EmbedProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CollectConsumer.consume()의 멱등성/커밋 분기 로직 검증
 * (실제 Kafka/DB/Redis 연동 없이 협력 객체를 전부 Mock으로 대체한 단위 테스트)
 */
@ExtendWith(MockitoExtension.class)
class CollectConsumerTest {

    private static final String FULL_NAME = "codescope/test-repo";

    @Mock
    private DuplicateCheckService duplicateCheckService;

    @Mock
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Mock
    private EmbedProducer embedProducer;

    @Mock
    private Acknowledgment ack;

    @InjectMocks
    private CollectConsumer collectConsumer;

    @Test
    @DisplayName("tryLock 실패 시 save()는 호출되지 않고 ack.acknowledge()만 호출된다")
    void consume_tryLock_실패_시_save_스킵() {
        // given
        CollectMessage message = createMessage();
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(false);

        // when
        collectConsumer.consume(message, ack);

        // then
        verify(githubRepositoryJpaRepository, never()).save(any());
        verify(embedProducer, never()).publish(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("tryLock 성공 후 save()가 정상 처리되면 임베딩 이벤트를 발행하고 ack.acknowledge()를 호출한다")
    void consume_저장_성공_시_임베딩_발행_후_ack() {
        // given
        CollectMessage message = createMessage();
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(true);
        given(githubRepositoryJpaRepository.save(any(GithubRepository.class)))
                .willReturn(mockRepository());

        // when
        collectConsumer.consume(message, ack);

        // then
        verify(githubRepositoryJpaRepository).save(any(GithubRepository.class));
        verify(embedProducer).publish(FULL_NAME);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("tryLock 성공 후 save()가 DataIntegrityViolationException을 던지면(DB 레벨 중복) " +
            "임베딩 이벤트는 발행하지 않고 ack.acknowledge()만 호출한다")
    void consume_DB_중복_감지_시_임베딩_발행_스킵_후_ack() {
        // given
        CollectMessage message = createMessage();
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(true);
        willThrow(new DataIntegrityViolationException("duplicate key"))
                .given(githubRepositoryJpaRepository).save(any(GithubRepository.class));

        // when
        collectConsumer.consume(message, ack);

        // then
        verify(embedProducer, never()).publish(any());
        verify(ack).acknowledge();
    }

    private CollectMessage createMessage() {
        return new CollectMessage(
                "test-repo",
                FULL_NAME,
                "테스트용 레포",
                "Java",
                100,
                20,
                5,
                "https://github.com/" + FULL_NAME
        );
    }

    private GithubRepository mockRepository() {
        return GithubRepository.builder()
                .name("test-repo")
                .fullName(FULL_NAME)
                .description("테스트용 레포")
                .language("Java")
                .starCount(100)
                .forkCount(20)
                .openIssueCount(5)
                .githubUrl("https://github.com/" + FULL_NAME)
                .build();
    }
}
