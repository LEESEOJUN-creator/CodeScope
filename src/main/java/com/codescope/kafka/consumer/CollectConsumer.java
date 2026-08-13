package com.codescope.kafka.consumer;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.service.DuplicateCheckService;
import com.codescope.kafka.dto.CollectMessage;
import com.codescope.kafka.producer.EmbedProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectConsumer {

    private final DuplicateCheckService duplicateCheckService;
    private final GithubRepositoryJpaRepository githubRepositoryJpaRepository;
    private final EmbedProducer embedProducer;

    // 재시도/DLT를 @RetryableTopic에 위임한다.
    // 왜: 기존에는 예외를 던지면 "Kafka가 재전달해준다"고 봤지만, 실제로는
    //   Spring Kafka 기본 DefaultErrorHandler가 백오프 없이 10회 즉시 재시도한 뒤
    //   로그만 남기고 오프셋을 커밋해 메시지를 버렸다(코드리뷰 B).
    //   @RetryableTopic은 실패분을 별도 retry 토픽으로 넘겨 지수 백오프로
    //   재시도하고, 끝내 실패하면 DLT에 보존해 사후 추적이 가능하다.
    // exclude: 4xx는 같은 요청을 반복해도 결과가 절대 바뀌지 않으므로
    //   재시도 없이 즉시 DLT로 보낸다(Rate Limit만 소모하는 헛시도 방지).
    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            exclude = {
                    HttpClientErrorException.NotFound.class,
                    HttpClientErrorException.Unauthorized.class,
                    HttpClientErrorException.Forbidden.class
            },
            // 토픽별로 하나의 DLT만 두어 codescope.collect-dlt 로 수렴시킨다
            // (기본값은 재시도 횟수만큼 접미사가 붙어 토픽이 늘어남)
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
    )
    @KafkaListener(
            topics = "codescope.collect",
            groupId = "collect-group",
            concurrency = "3"
    )
    public void consume(CollectMessage message, Acknowledgment ack) {
        String fullName = message.fullName();

        // 1. 최근 9분 이내에 이미 처리 완료된 레포면 DB까지 갈 필요가 없다.
        //    같은 수집 주기 안에서 중복 유입된 메시지를 여기서 컷.
        if (duplicateCheckService.isRecentlyCompleted(fullName)) {
            log.debug("최근 처리 완료된 레포라 스킵: fullName={}", fullName);
            ack.acknowledge();
            return;
        }

        // 2. 처리 권한 획득 실패 = 다른 처리자가 지금 이 레포를 붙잡고 있다.
        //    ack하지 않고 그냥 반환한다 — 여기서 ack해버리면 아직 저장되지도
        //    않은 메시지를 "처리 완료"로 커밋해 유실시키는 것이 이전 버그였다.
        if (!duplicateCheckService.tryLock(fullName)) {
            log.debug("다른 처리자가 점유 중이라 이번 메시지는 넘김: fullName={}", fullName);
            return;
        }

        try {
            // 3~4. 있으면 갱신, 없으면 신규 저장.
            //   왜: 이전에는 신규 save()만 하고 중복이면 스킵해서, 두 번째
            //   사이클부터는 아무 일도 일어나지 않았고 starCount 등이 최초
            //   수집값에 영원히 고정됐다(코드리뷰 C). "실시간 트렌드"라는
            //   서비스 정체성과 정면으로 충돌하던 지점.
            Optional<GithubRepository> existing =
                    githubRepositoryJpaRepository.findByFullName(fullName);

            if (existing.isPresent()) {
                existing.get().update(
                        message.starCount(),
                        message.forkCount(),
                        message.openIssueCount()
                );
                // JPA 변경 감지(dirty checking)에 의존하지 않고 명시적으로 저장한다.
                // 이 리스너 메서드에는 @Transactional이 없어 영속성 컨텍스트가
                // 메서드 경계까지 유지된다는 보장이 없기 때문.
                githubRepositoryJpaRepository.save(existing.get());
                log.info("레포 갱신 완료: fullName={}, stars={}", fullName, message.starCount());
            } else {
                githubRepositoryJpaRepository.save(toEntity(message));
                log.info("레포 신규 저장 완료: fullName={}", fullName);
            }

            // 5. 성공 경로: 임베딩 이벤트 발행 → 완료 표식 → 커밋
            embedProducer.publish(fullName);
            duplicateCheckService.markCompleted(fullName);
            ack.acknowledge();

        } catch (DataIntegrityViolationException e) {
            // 6. 조회~저장 사이에 다른 인스턴스가 먼저 넣은 경우(DB unique 최종 방어선).
            //    실패가 아니라 정상적인 멱등 처리이므로 완료로 간주하고 커밋한다.
            log.info("DB 레벨 중복 감지, 완료 처리: fullName={}", fullName);
            duplicateCheckService.markCompleted(fullName);
            ack.acknowledge();

        } catch (Exception e) {
            // 7. 그 외 예외(DB 연결 실패 등): 완료 표식을 남기지 않고,
            //    처리 중 락을 "즉시" 풀어준 뒤 예외를 그대로 다시 던진다.
            //
            //    왜 TTL 만료를 기다리지 않고 직접 푸는가:
            //    @RetryableTopic의 재시도는 1초 → 2초 뒤에 오는데 처리 중 락의
            //    TTL은 1분이다. 락이 자연 만료되기를 기다리면 모든 재시도가
            //    tryLock()에서 막혀, 저장을 한 번도 재시도하지 못한 채 DLT로
            //    직행한다(코드리뷰 K). 여기서 풀어야 재시도가 실제로 동작한다.
            //
            //    ack하지 않으므로 @RetryableTopic이 retry 토픽으로 넘겨 재시도하고,
            //    3회를 모두 소진하면 DLT에 보존되어 사후 추적이 가능하다.
            //    ← 이 지점이 코드리뷰 A(락 미해제로 인한 메시지 유실)의 핵심 해결점.
            duplicateCheckService.releaseLock(fullName);
            throw e;
        }
    }

    @DltHandler
    public void handleDlt(CollectMessage message,
                          @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage) {
        // 재시도까지 소진된 메시지를 사람이 추적할 수 있게 남긴다.
        // (이전에는 조용히 버려져 무엇이 왜 실패했는지 알 방법이 없었다)
        log.error("[DLT] 레포 수집 최종 실패: fullName={}, reason={}",
                message.fullName(), errorMessage);
    }

    private GithubRepository toEntity(CollectMessage message) {
        return GithubRepository.builder()
                .name(message.name())
                .fullName(message.fullName())
                .description(message.description())
                .language(message.language())
                .starCount(message.starCount())
                .forkCount(message.forkCount())
                .openIssueCount(message.openIssueCount())
                .githubUrl(message.githubUrl())
                .build();
    }
}
