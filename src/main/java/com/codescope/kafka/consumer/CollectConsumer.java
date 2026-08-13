package com.codescope.kafka.consumer;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.service.DuplicateCheckService;
import com.codescope.kafka.dto.CollectMessage;
import com.codescope.kafka.producer.EmbedProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectConsumer {

    private final DuplicateCheckService duplicateCheckService;
    private final GithubRepositoryJpaRepository githubRepositoryJpaRepository;
    private final EmbedProducer embedProducer;

    @KafkaListener(
            topics = "codescope.collect",
            groupId = "collect-group",
            concurrency = "3"
    )
    public void consume(CollectMessage message, Acknowledgment ack) {
        // Redis setIfAbsent(SET NX)로 확인+점유를 원자적으로 처리 (TOCTOU 방지)
        // 이미 처리 중/완료(TTL 10분 이내 재유입)면 여기서 바로 스킵
        if (!duplicateCheckService.tryLock(message.fullName())) {
            log.info("이미 처리 중이거나 최근 처리된 레포라 스킵: repoName={}", message.fullName());
            ack.acknowledge();
            return;
        }

        try {
            GithubRepository repository = GithubRepository.builder()
                    .name(message.name())
                    .fullName(message.fullName())
                    .description(message.description())
                    .language(message.language())
                    .starCount(message.starCount())
                    .forkCount(message.forkCount())
                    .openIssueCount(message.openIssueCount())
                    .githubUrl(message.githubUrl())
                    .build();

            githubRepositoryJpaRepository.save(repository);
            log.info("레포 저장 완료: fullName={}", message.fullName());

            // 저장 성공 직후 임베딩 파이프라인으로 이어지도록 발행
            // 왜 지금은 실패를 로그만 남기고 넘어가는가: 발행 실패 시 재시도까지
            //   여기서 처리하면 컨슈머 처리 시간이 늘어나고 커밋 타이밍과 얽힘.
            //   실패 로깅은 EmbedProducer.publish() 내부 whenComplete에서 이미
            //   처리되므로 여기선 결과를 기다리지 않고 그대로 진행.
            //   재시도/보정 전략은 Day 25에서 임베딩 파이프라인을 마저 채울 때
            //   함께 재검토 예정
            embedProducer.publish(message.fullName());

            ack.acknowledge();
        } catch (DataIntegrityViolationException e) {
            // Redis 락은 TTL 10분짜리 최선 노력 방지책이라, 락 만료 후 재유입되거나
            // 여러 Consumer 인스턴스가 겹치는 극단적 케이스에서 DB unique(fullName)
            // 제약이 최종 방어선 역할을 함. 이건 실패가 아니라 정상적인 멱등 처리
            log.info("DB 레벨 중복 감지, 스킵: fullName={}", message.fullName());
            ack.acknowledge();
        }
        // 그 외 예외(DB 연결 실패 등)는 여기서 잡지 않고 그대로 던진다.
        // ack.acknowledge()를 호출하지 않아야 커밋이 안 되고, Kafka가 재전달한다.
    }
}
