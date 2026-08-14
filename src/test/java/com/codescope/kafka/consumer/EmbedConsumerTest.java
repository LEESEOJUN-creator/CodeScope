package com.codescope.kafka.consumer;

import com.codescope.client.llm.EmbeddingService;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.RepoEmbedding;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.repository.RepoEmbeddingJpaRepository;
import com.codescope.infra.github.GithubApiClient;
import com.codescope.kafka.dto.EmbedMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Optional;

import static com.codescope.domain.repo.entity.GithubRepository.ProcessStatus.EMBEDDED;
import static com.codescope.domain.repo.entity.GithubRepository.ProcessStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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

    @InjectMocks
    private EmbedConsumer embedConsumer;

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
    @DisplayName("CollectConsumer가 아직 저장하지 않은(DB에 없는) 레포면 예외를 던지고 ack하지 않는다")
    void consume_레포_없으면_예외_전파() {
        // given
        given(githubRepositoryJpaRepository.findByFullName(FULL_NAME))
                .willReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> embedConsumer.consume(new EmbedMessage(FULL_NAME), ack))
                .isInstanceOf(IllegalStateException.class);

        verify(embeddingService, never()).embed(any(), any());
        verify(ack, never()).acknowledge();
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
