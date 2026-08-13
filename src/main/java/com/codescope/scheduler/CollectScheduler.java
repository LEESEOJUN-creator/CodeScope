package com.codescope.scheduler;

import com.codescope.infra.github.GithubApiClient;
import com.codescope.infra.github.TrendingRepoDto;
import com.codescope.kafka.dto.CollectMessage;
import com.codescope.kafka.producer.CollectProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectScheduler {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final GithubApiClient githubApiClient;
    private final CollectProducer collectProducer;

    // 왜 fixedDelay인가: fixedRate는 이전 실행이 안 끝나도 다음 실행을 시작해
    //   재시도까지 포함한 이번 사이클 처리 중에 겹쳐 돌 위험이 있음
    //   fixedDelay는 "이전 실행 종료 후" 10분을 재므로 겹침 없이 안전
    @Scheduled(fixedDelay = 600_000)
    public void collect() {
        List<TrendingRepoDto> trendingRepos;

        // 1. GitHub 검색 자체 실패(네트워크, Rate Limit 초과 등)는 이번 사이클
        //    전체를 포기하고 다음 10분 사이클에 자연스럽게 재시도되도록 함
        //    (여기서 개별 재시도를 하면 스케줄러 스레드가 오래 묶여
        //     다음 사이클과 겹칠 위험이 커짐)
        try {
            trendingRepos = githubApiClient.searchTrending();
        } catch (Exception e) {
            log.error("GitHub 트렌드 검색 실패, 이번 사이클 스킵", e);
            return;
        }

        if (trendingRepos.isEmpty()) {
            log.info("이번 사이클에 수집할 트렌드 레포 없음");
            return;
        }

        // 2~3. 변환 후 발행. 1차 시도에서 실패한 것만 모아서 재시도 대상으로 넘김
        List<TrendingRepoDto> firstAttemptFailures = publishAll(trendingRepos);

        if (firstAttemptFailures.isEmpty()) {
            log.info("전체 발행 성공: 건수={}", trendingRepos.size());
            return;
        }

        // 4. 재시도는 1회만 수행
        //    왜: Kafka 발행 실패는 대개 일시적 네트워크 이슈나 브로커 순간 부하인데,
        //    같은 스케줄러 사이클 안에서 계속 재시도하면 스레드가 묶여
        //    다음 10분 사이클과 겹칠 수 있음. 1회만 더 시도하고 그래도 실패하면
        //    "이번 사이클은 여기까지"로 포기 → 다음 10분 사이클에서
        //    GitHub 재검색부터 다시 시작하는 편이 더 단순하고 안전함
        log.warn("1차 발행 실패 건, 재시도 시작: 건수={}", firstAttemptFailures.size());
        List<TrendingRepoDto> finalFailures = publishAll(firstAttemptFailures);

        if (!finalFailures.isEmpty()) {
            finalFailures.forEach(dto ->
                    log.error("재시도까지 발행 실패, 다음 사이클로 넘김: fullName={}", dto.fullName()));
        }
    }

    // dto 목록을 CollectMessage로 변환 후 발행하고, 발행 실패한 dto만 모아 반환
    private List<TrendingRepoDto> publishAll(List<TrendingRepoDto> dtos) {
        List<TrendingRepoDto> failures = new ArrayList<>();

        for (TrendingRepoDto dto : dtos) {
            CollectMessage message = toCollectMessage(dto);

            // 왜 동기로 확인하는가: 발행 결과를 여기서 즉시 확인해야
            //   "실패한 것만" 골라서 재시도 목록에 넣을 수 있음
            //   whenComplete 콜백만 쓰면 이 메서드가 이미 반환된 뒤 비동기로
            //   실패가 찍히므로, 실패 목록을 만드는 로직과 타이밍이 안 맞음
            //   Kafka 발행 자체는 원래도 빠르고(브로커까지 왕복 수십~수백ms),
            //   10분 주기 배치성 스케줄러라 짧은 블로킹은 문제 되지 않음
            try {
                collectProducer.publish(message).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Kafka 발행 실패: fullName={}", dto.fullName(), e);
                failures.add(dto);
            }
        }

        return failures;
    }

    private CollectMessage toCollectMessage(TrendingRepoDto dto) {
        return new CollectMessage(
                dto.name(),
                dto.fullName(),
                dto.description(),
                dto.language(),
                dto.starsCount(),
                dto.forksCount(),
                dto.openIssuesCount(),
                dto.htmlUrl()
        );
    }
}
