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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

// Day 25: README 수집 + Ollama 임베딩 생성 + pgvector 저장까지 완성.
// Day 26: poll 스레드 동기 처리로 인한 리밸런싱 폭주(docs/troubleshooting.md
//   참고)를 근본 해결하기 위해, 실제 처리를 embedWorkerExecutor로 위임하는
//   구조로 리팩토링.
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedConsumer {

    // 기존 @RetryableTopic(attempts=3, backoff=1000ms×2.0)와 동일한 정책을
    // 워커 스레드 안에서 재현한다. 상수명도 그대로 맞춰 정책이 하나임을
    // 드러낸다.
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_DELAY_MS = 1000L;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final GithubApiClient githubApiClient;
    private final EmbeddingService embeddingService;
    private final GithubRepositoryJpaRepository githubRepositoryJpaRepository;
    private final RepoEmbeddingJpaRepository repoEmbeddingJpaRepository;
    private final ExecutorService embedWorkerExecutor;

    // 왜 @RetryableTopic을 여전히 두는가: 이제 이 리스너 메서드 자체는
    // "작업을 큐에 제출"만 하고 정상 반환하므로, 임베딩 처리 실패는 더 이상
    // 여기로 전파되지 않는다(아래 processWithRetry가 전담). 이 애노테이션이
    // 실제로 담당하는 것은 딱 하나 — embedWorkerExecutor의 큐가 가득 차서
    // RejectedExecutionException이 던져지는 경우(백프레셔)뿐이다. 그 경우엔
    // 기존과 동일하게 재시도 토픽으로 넘어가 지수 백오프 후 재시도되고,
    // 그래도 계속 차 있으면 DLT로 간다.
    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
    )
    @KafkaListener(
            topics = "codescope.embed",
            groupId = "embed-group",
            concurrency = "3"
    )
    public void consume(EmbedMessage message, Acknowledgment ack) {
        // 왜 여기서 ack하지도, 예외를 삼키지도 않는가: 오프셋 커밋은
        // 반드시 워커가 실제 임베딩까지 완료(성공 또는 최종 실패 확정)한
        // 뒤에만 이뤄져야 한다. 그래야 재시도 중이던 메시지가 성급하게
        // "처리 완료"로 커밋되는 일이 없다.
        //
        // 트레이드오프(의도적으로 감수함): 이 작업 큐는 인메모리다. 앱이
        // 재시작되면 이미 큐에 들어갔지만 아직 워커가 다 처리하지 못한
        // 작업은 유실될 수 있다. 하지만 이 시점엔 해당 메시지가 아직
        // 커밋되지 않은 상태이므로, 재시작 후 Kafka가 커밋된 오프셋
        // 이후부터 다시 전달해 재처리된다(at-least-once 유지됨) — 단,
        // 이미 큐에 있었지만 아직 poll에서 안 넘어간 것들은 재시작 후
        // 재시도된다.
        try {
            embedWorkerExecutor.execute(() -> processWithRetry(message, ack));
        } catch (RejectedExecutionException e) {
            log.warn("임베딩 작업 큐 포화, 재시도 위임: fullName={}", message.fullName());
            throw e;
        }
    }

    // 워커 스레드에서 실행된다. poll 스레드와 완전히 분리돼 있으므로
    // 여기서 재시도 대기(백오프)로 오래 블로킹돼도 Kafka 하트비트/
    // max.poll.interval.ms에는 아무 영향이 없다.
    private void processWithRetry(EmbedMessage message, Acknowledgment ack) {
        String fullName = message.fullName();
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                doEmbed(fullName);
                ack.acknowledge();
                return;
            } catch (HttpClientErrorException.NotFound
                     | HttpClientErrorException.Unauthorized
                     | HttpClientErrorException.Forbidden
                     | IllegalStateException e) {
                // 4xx는 같은 요청을 반복해도 결과가 바뀌지 않으므로 재시도 없이
                // 즉시 최종 실패로 확정한다(기존 @RetryableTopic의 exclude와
                // 동일한 취지).
                //
                // IllegalStateException(doEmbed의 "레포가 DB에 없음")도 같은
                // 분류다(2026-08-16 확인). CollectConsumer.consume()에는
                // @Transactional이 없어 githubRepositoryJpaRepository.save()
                // 호출 자체가 그 자리에서 즉시 커밋되는 독립 트랜잭션이고,
                // embedProducer.publish()는 그 save()가 리턴된(=커밋 완료된)
                // "다음"에만 순차 호출된다 — 별도의 Kafka 트랜잭션 동기화
                // 장치 없이도 순차 실행 구조 자체가 "커밋 후에만 발행"을
                // 보장한다. 즉 EmbedConsumer가 메시지를 받는 시점엔 레포가
                // 이미 DB에 있는 게 정상이라, "레포 없음"은 타이밍 레이스가
                // 아니라 영구적 상황(발행 이후 삭제됐거나, dev 환경 DB 리셋
                // 후 Kafka에 남은 stale 메시지 등)이다. 재시도로 해결되지
                // 않으므로 4xx와 동일하게 즉시 실패 확정한다.
                lastException = e;
                break;
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("임베딩 처리 실패, 재시도 예정: fullName={}, attempt={}/{}",
                            fullName, attempt, MAX_ATTEMPTS, e);
                    sleepBackoff(attempt);
                }
            }
        }

        handleFinalFailure(fullName, lastException);
        ack.acknowledge();
    }

    private void doEmbed(String fullName) {
        GithubRepository repository = githubRepositoryJpaRepository.findByFullName(fullName)
                .orElseThrow(() -> new IllegalStateException(
                        "임베딩 대상 레포가 DB에 없습니다: fullName=" + fullName));

        String readme = githubApiClient.fetchReadme(fullName);
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
        githubRepositoryJpaRepository.save(repository);

        log.info("임베딩 저장 완료: fullName={}, dim={}", fullName, embedding.length);
    }

    // attempt=1 → 1000ms, attempt=2 → 2000ms (delay × multiplier^(attempt-1))
    private void sleepBackoff(int attempt) {
        long delayMs = (long) (BACKOFF_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, attempt - 1));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 재시도를 모두 소진한 시점에만 FAILED로 확정한다. RAG 추천 쿼리는
    // status=EMBEDDED만 필터링하므로, 여기서 표시해 두지 않으면 반쪽짜리
    // (COLLECTED인데 벡터 없는) 레포가 계속 "아직 처리 중"으로 오인될 수 있다.
    private void handleFinalFailure(String fullName, Exception reason) {
        log.error("[임베딩 최종 실패] fullName={}, reason={}", fullName,
                reason != null ? reason.getMessage() : "unknown", reason);

        githubRepositoryJpaRepository.findByFullName(fullName)
                .ifPresent(repository -> {
                    repository.markFailed();
                    githubRepositoryJpaRepository.save(repository);
                });
    }

    // 큐 포화(RejectedExecutionException)로 재시도까지 모두 소진된, 실제로는
    // 거의 발생하지 않을 것으로 예상되는 경로. 사람이 추적할 수 있게 기록만
    // 남긴다 — FAILED 확정은 여기서도 동일하게 처리해 일관성을 유지한다.
    @DltHandler
    public void handleDlt(EmbedMessage message,
                          @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage) {
        log.error("[DLT] 임베딩 작업 큐 제출 최종 실패: fullName={}, reason={}",
                message.fullName(), errorMessage);

        githubRepositoryJpaRepository.findByFullName(message.fullName())
                .ifPresent(repository -> {
                    repository.markFailed();
                    githubRepositoryJpaRepository.save(repository);
                });
    }
}
