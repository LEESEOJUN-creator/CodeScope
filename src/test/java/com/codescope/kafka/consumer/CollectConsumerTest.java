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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CollectConsumer.consume()의 멱등성/갱신/커밋 분기 검증
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
    @DisplayName("최근 처리 완료된 레포면 DB 접근 없이 ack만 하고 끝낸다")
    void consume_최근_완료된_레포는_스킵() {
        // given
        given(duplicateCheckService.isRecentlyCompleted(FULL_NAME)).willReturn(true);

        // when
        collectConsumer.consume(createMessage(), ack);

        // then
        verify(githubRepositoryJpaRepository, never()).findByFullName(any());
        verify(githubRepositoryJpaRepository, never()).save(any());
        verify(embedProducer, never()).publish(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("다른 처리자가 점유 중이면 ack하지 않고 반환한다(아직 저장되지 않았으므로 커밋 금지)")
    void consume_점유_중이면_ack_없이_반환() {
        // given
        given(duplicateCheckService.isRecentlyCompleted(FULL_NAME)).willReturn(false);
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(false);

        // when
        collectConsumer.consume(createMessage(), ack);

        // then
        verify(githubRepositoryJpaRepository, never()).save(any());
        verify(embedProducer, never()).publish(any());
        // 핵심: 저장되지 않았는데 커밋하면 메시지가 유실된다
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("신규 레포면 저장 후 임베딩 발행 → 완료 표식 → ack 순서로 처리한다")
    void consume_신규_저장() {
        // given
        given(duplicateCheckService.isRecentlyCompleted(FULL_NAME)).willReturn(false);
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(true);
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.empty());

        // when
        collectConsumer.consume(createMessage(), ack);

        // then
        verify(githubRepositoryJpaRepository).save(any(GithubRepository.class));
        verify(embedProducer).publish(FULL_NAME);
        verify(duplicateCheckService).markCompleted(FULL_NAME);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("이미 존재하는 레포면 신규 저장이 아니라 star/fork/issue 수를 갱신한다")
    void consume_기존_레포_갱신() {
        // given
        GithubRepository existing = createEntity(100, 20, 5);
        given(duplicateCheckService.isRecentlyCompleted(FULL_NAME)).willReturn(false);
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(true);
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.of(existing));

        // 새로 들어온 메시지는 별 수가 늘어난 상태
        CollectMessage updated = new CollectMessage(
                "test-repo", FULL_NAME, "테스트용 레포", "Java",
                999, 77, 33, "https://github.com/" + FULL_NAME);

        // when
        collectConsumer.consume(updated, ack);

        // then: 엔티티에 실제로 새 수치가 반영됐는지 확인
        assertThat(existing.getStarCount()).isEqualTo(999);
        assertThat(existing.getForkCount()).isEqualTo(77);
        assertThat(existing.getOpenIssueCount()).isEqualTo(33);
        verify(githubRepositoryJpaRepository).save(existing);
        verify(embedProducer).publish(FULL_NAME);
        verify(duplicateCheckService).markCompleted(FULL_NAME);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("DB 레벨 중복(DataIntegrityViolationException)은 정상 멱등 처리로 보고 완료 표식 후 ack한다")
    void consume_DB_중복은_완료_처리() {
        // given
        given(duplicateCheckService.isRecentlyCompleted(FULL_NAME)).willReturn(false);
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(true);
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.empty());
        willThrow(new DataIntegrityViolationException("duplicate key"))
                .given(githubRepositoryJpaRepository).save(any(GithubRepository.class));

        // when
        collectConsumer.consume(createMessage(), ack);

        // then
        verify(embedProducer, never()).publish(any());
        verify(duplicateCheckService).markCompleted(FULL_NAME);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("그 외 예외(DB 장애 등)는 처리 중 락을 즉시 풀고, 완료 표식도 ack도 없이 예외를 전파한다")
    void consume_일시적_장애는_락_해제_후_커밋_없이_전파() {
        // given
        given(duplicateCheckService.isRecentlyCompleted(FULL_NAME)).willReturn(false);
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(true);
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.empty());
        willThrow(new RuntimeException("DB 커넥션 실패"))
                .given(githubRepositoryJpaRepository).save(any(GithubRepository.class));

        // when / then
        assertThatThrownBy(() -> collectConsumer.consume(createMessage(), ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 커넥션 실패");

        // 핵심 회귀 방지 1: 처리 중 락을 즉시 풀지 않으면, 1~2초 뒤 도착하는
        // 재시도가 TTL 1분짜리 락에 막혀 저장을 재시도하지 못하고 DLT로 직행한다
        verify(duplicateCheckService).releaseLock(FULL_NAME);

        // 핵심 회귀 방지 2: 완료 표식이 남으면 다음 시도가 영영 스킵되고,
        // ack하면 저장되지 않은 메시지가 커밋되어 유실된다
        verify(duplicateCheckService, never()).markCompleted(any());
        verify(ack, never()).acknowledge();
        verify(embedProducer, never()).publish(any());
    }

    @Test
    @DisplayName("정상 처리 경로에서는 처리 중 락을 해제하지 않는다(완료 표식이 중복 유입을 막는 역할)")
    void consume_성공_시에는_락_해제_안_함() {
        // given
        given(duplicateCheckService.isRecentlyCompleted(FULL_NAME)).willReturn(false);
        given(duplicateCheckService.tryLock(FULL_NAME)).willReturn(true);
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.empty());

        // when
        collectConsumer.consume(createMessage(), ack);

        // then
        verify(duplicateCheckService, never()).releaseLock(any());
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

    private GithubRepository createEntity(int stars, int forks, int issues) {
        return GithubRepository.builder()
                .name("test-repo")
                .fullName(FULL_NAME)
                .description("테스트용 레포")
                .language("Java")
                .starCount(stars)
                .forkCount(forks)
                .openIssueCount(issues)
                .githubUrl("https://github.com/" + FULL_NAME)
                .build();
    }
}
