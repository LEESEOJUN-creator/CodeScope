package com.codescope.kafka.consumer;

import com.codescope.client.llm.EmbeddingService;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.RepoEmbedding;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.repository.RepoEmbeddingJpaRepository;
import com.codescope.infra.github.GithubApiClient;
import com.codescope.kafka.dto.EmbedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static com.codescope.domain.repo.entity.GithubRepository.ProcessStatus.EMBEDDED;
import static com.codescope.domain.repo.entity.GithubRepository.ProcessStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * EmbedConsumer.consume()/handleDlt()의 저장·상태 전환 분기 검증
 * (실제 Kafka/DB/Ollama 연동 없이 협력 객체를 전부 Mock으로 대체한 단위 테스트)
 */
@ExtendWith(MockitoExtension.class)
class EmbedConsumerTest {

    private static final String FULL_NAME = "codescope/test-repo";
    private static final float[] EMBEDDING = new float[]{0.1f, 0.2f, 0.3f};

    @Mock
    private GithubApiClient githubApiClient;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Mock
    private RepoEmbeddingJpaRepository repoEmbeddingJpaRepository;

    @Mock
    private Acknowledgment ack;

    // Day 26 리팩토링으로 EmbedConsumer 생성자에 추가된 의존성. 이 Mock이
    // 빠져 있으면 @InjectMocks가 이 자리를 null로 채워, consume() 호출 시
    // NullPointerException이 난다(2026-08-16 전체 테스트 실행 중 실측 발견).
    @Mock
    private ExecutorService embedWorkerExecutor;

    @InjectMocks
    private EmbedConsumer embedConsumer;

    // consume()은 실제로 embedWorkerExecutor에 작업(Runnable)을 위임하고 즉시
    // 반환하지만, 이 테스트들은 위임된 작업이 끝난 뒤의 결과(ack.acknowledge(),
    // status 변경 등)를 consume() 호출 직후 바로 검증한다. 그러려면 execute()에
    // 전달된 Runnable을 그 자리에서 동기로 실행하도록 스텁해야 한다.
    // handleDlt_status_FAILED로_확정 테스트는 consume()을 호출하지 않아 이
    // 스텁을 안 쓰므로, strict stub 검증(UnnecessaryStubbingException)에
    // 걸리지 않게 lenient()로 선언한다.
    @BeforeEach
    void runSubmittedTaskSynchronously() {
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(embedWorkerExecutor).execute(any());
    }

    @Test
    @DisplayName("신규 임베딩이면 저장 후 status를 EMBEDDED로 바꾸고 ack한다")
    void consume_신규_임베딩_저장() {
        // given
        GithubRepository repository = createRepository();
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.of(repository));
        given(githubApiClient.fetchReadme(FULL_NAME)).willReturn("# readme");
        given(embeddingService.embed(FULL_NAME, "# readme")).willReturn(EMBEDDING);
        given(repoEmbeddingJpaRepository.findByRepositoryId(repository.getId()))
                .willReturn(Optional.empty());

        // when
        embedConsumer.consume(new EmbedMessage(FULL_NAME), ack);

        // then
        verify(repoEmbeddingJpaRepository).save(any(RepoEmbedding.class));
        assertThat(repository.getProcessStatus()).isEqualTo(EMBEDDED);
        verify(githubRepositoryJpaRepository).save(repository);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("이미 임베딩이 있으면 새로 저장하지 않고 기존 값을 갱신한다")
    void consume_기존_임베딩_갱신() {
        // given
        GithubRepository repository = createRepository();
        RepoEmbedding existingEmbedding = RepoEmbedding.of(repository, new float[]{0f, 0f, 0f});
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.of(repository));
        given(githubApiClient.fetchReadme(FULL_NAME)).willReturn("# readme");
        given(embeddingService.embed(FULL_NAME, "# readme")).willReturn(EMBEDDING);
        given(repoEmbeddingJpaRepository.findByRepositoryId(repository.getId()))
                .willReturn(Optional.of(existingEmbedding));

        // when
        embedConsumer.consume(new EmbedMessage(FULL_NAME), ack);

        // then: 기존 엔티티가 그대로 갱신되어 save됐는지(참조 동일성) 확인 -
        // RepoEmbedding.of()로 새로 만든 다른 인스턴스가 저장됐다면 이 검증은
        // 실패한다(Mockito verify는 equals 미오버라이드 시 참조 동일성으로 매칭).
        assertThat(existingEmbedding.getEmbedding()).isEqualTo(EMBEDDING);
        verify(repoEmbeddingJpaRepository).save(existingEmbedding);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("레포가 DB에 없으면 재시도 없이 즉시 실패 확정하고 ack한다(2026-08-16: 4xx와 동일하게 재시도 분류에서 제외)")
    void consume_레포_없으면_재시도_없이_즉시_FAILED_확정() {
        // given
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.empty());

        // when
        // CollectConsumer.consume()에 @Transactional이 없어 save()가 그 자리에서
        // 즉시 커밋되고, embedProducer.publish()는 그 이후에만 순차 호출된다
        // (별도 Kafka 트랜잭션 동기화 없이도 순차 실행 구조 자체가 "커밋 후
        // 발행"을 보장함 — 2026-08-16 확인). 즉 EmbedConsumer 입장에서
        // "레포 없음"은 타이밍 레이스가 아니라 영구적 상황이라, 예외가
        // consume() 밖으로 전파되지 않고(Day 26 리팩토링 이후 재시도는 워커
        // 스레드 내부 처리로 완전히 내부화됨) processWithRetry가 안에서
        // 흡수하되 "재시도는 하지 않는다"만 검증한다.
        embedConsumer.consume(new EmbedMessage(FULL_NAME), ack);

        // then
        // doEmbed()의 findByFullName 호출이 딱 1번만 일어났는지로 "재시도 안 함"을 증명한다.
        // 만약 4xx 분류에서 빠져 3회 재시도됐다면 doEmbed에서 3번 + handleFinalFailure에서
        // 1번 = 총 4번 호출됐을 것이다. 즉시 실패면 doEmbed 1번 + handleFinalFailure 1번 = 2번.
        verify(githubRepositoryJpaRepository, times(2)).findByFullName(FULL_NAME);
        // 존재하지 않는 레포이므로 마킹할 대상 자체가 없어(handleFinalFailure의
        // ifPresent가 no-op) save()는 끝까지 한 번도 호출되지 않는다.
        verify(githubRepositoryJpaRepository, never()).save(any());
        verify(embeddingService, never()).embed(any(), any());
        // 최종 실패도 "처리 완료"로 간주해 오프셋을 커밋한다(재시도는 Kafka
        // 재전달이 아니라 이제 processWithRetry 내부 루프가 전담하므로,
        // 여기서 끝난 이상 더는 재전달받을 이유가 없다).
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("DLT까지 온 메시지는 해당 레포 status를 FAILED로 확정한다")
    void handleDlt_status_FAILED로_확정() {
        // given
        GithubRepository repository = createRepository();
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.of(repository));

        // when
        embedConsumer.handleDlt(new EmbedMessage(FULL_NAME), "Ollama 연결 실패");

        // then
        assertThat(repository.getProcessStatus()).isEqualTo(FAILED);
        verify(githubRepositoryJpaRepository).save(repository);
    }

    private GithubRepository createRepository() {
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
