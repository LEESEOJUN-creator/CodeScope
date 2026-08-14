package com.codescope.kafka.consumer;

import com.codescope.client.llm.EmbeddingService;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.RepoEmbedding;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.repository.RepoEmbeddingJpaRepository;
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

import java.util.Optional;

// Day 25: README 수집 + Ollama 임베딩 생성 + pgvector 저장까지 완성.
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedConsumer {

    private final GithubApiClient githubApiClient;
    private final EmbeddingService embeddingService;
    private final GithubRepositoryJpaRepository githubRepositoryJpaRepository;
    private final RepoEmbeddingJpaRepository repoEmbeddingJpaRepository;

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
        String fullName = message.fullName();

        // CollectConsumer가 이 메시지를 발행하기 전에 반드시 저장을 마치므로
        // (CollectConsumer 5번 단계: save 이후 embedProducer.publish) 여기서
        // 못 찾으면 파이프라인 순서 자체가 깨진 것 — 재시도해도 소용없는
        // 상황은 아니라(일시적으로 트랜잭션이 아직 안 보일 수도 있음)
        // 예외를 던져 재시도/DLT에 맡긴다.
        GithubRepository repository = githubRepositoryJpaRepository.findByFullName(fullName)
                .orElseThrow(() -> new IllegalStateException(
                        "임베딩 대상 레포가 DB에 없습니다: fullName=" + fullName));

        // 실패 시(404 등 제외) 예외를 그대로 던져 커밋하지 않는다.
        // @RetryableTopic이 일시적 오류는 지수 백오프로 재시도하고,
        // 재시도해도 소용없는 4xx는 즉시 DLT로 보낸다.
        String readme = githubApiClient.fetchReadme(fullName);

        // Ollama 연결 실패/타임아웃 등도 여기서 예외로 전파된다.
        // 재시도 중에는 status를 건드리지 않는다 — 성급하게 FAILED로
        // 바꾸면 다음 재시도가 성공해도 이미 잘못된 상태가 남는다.
        // FAILED 확정은 재시도를 모두 소진한 뒤 handleDlt()에서만 한다.
        float[] embedding = embeddingService.embed(fullName, readme);

        // 이미 임베딩이 있으면(재수집 사이클로 다시 들어온 경우) 갱신,
        // 없으면 신규 저장. repo_id UNIQUE 제약 때문에 무조건 save()만
        // 하면 두 번째부터 DataIntegrityViolationException이 난다.
        Optional<RepoEmbedding> existing =
                repoEmbeddingJpaRepository.findByRepositoryId(repository.getId());

        if (existing.isPresent()) {
            existing.get().updateEmbedding(embedding);
            repoEmbeddingJpaRepository.save(existing.get());
        } else {
            repoEmbeddingJpaRepository.save(RepoEmbedding.of(repository, embedding));
        }

        repository.markEmbedded();
        // JPA 변경 감지에 의존하지 않고 명시적으로 저장한다. 이 리스너
        // 메서드에는 @Transactional이 없어 영속성 컨텍스트가 메서드
        // 경계까지 유지된다는 보장이 없기 때문(CollectConsumer와 동일 이유).
        githubRepositoryJpaRepository.save(repository);

        log.info("임베딩 저장 완료: fullName={}, dim={}", fullName, embedding.length);

        ack.acknowledge();
    }

    @DltHandler
    public void handleDlt(EmbedMessage message,
                          @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage) {
        log.error("[DLT] 임베딩 생성 최종 실패: fullName={}, reason={}",
                message.fullName(), errorMessage);

        // 재시도 3회를 모두 소진한 시점에만 FAILED로 확정한다.
        // RAG 추천 쿼리는 status=EMBEDDED만 필터링하므로, 여기서 표시해
        // 두지 않으면 반쪽짜리(COLLECTED인데 벡터 없는) 레포가 계속
        // "아직 처리 중"으로 오인될 수 있다.
        githubRepositoryJpaRepository.findByFullName(message.fullName())
                .ifPresent(repository -> {
                    repository.markFailed();
                    githubRepositoryJpaRepository.save(repository);
                });
    }
}
