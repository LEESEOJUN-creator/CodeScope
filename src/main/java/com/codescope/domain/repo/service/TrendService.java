package com.codescope.domain.repo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendService {

    private static final String TRENDING_REPOS_KEY = "trending:repos";

    private final StringRedisTemplate redisTemplate;

    // 수집 시점의 스타 수를 랭킹 점수로 "덮어쓴다"(ZADD).
    // 왜 increaseScore(ZINCRBY)가 아닌가: 수집은 Kafka at-least-once라 같은 메시지가
    //   재전달될 수 있다. 누적(ZINCRBY)은 재전달마다 점수가 부풀지만, 덮어쓰기는 몇 번
    //   처리돼도 결과가 같다(멱등). 원래 랭킹에 점수를 쓰는 호출부가 아예 없어
    //   GET /api/repos/trending이 항상 빈 목록이었다(2026-10-13 발견) — CollectConsumer가
    //   저장 직후 이 메서드를 호출한다.
    // 한계: 점수 = 현재 스타 수라 "급상승"(기간 내 증가량)이 아니라 "수집된 트렌딩 레포 중
    //   인기 순"이다. 증가량 기반 랭킹은 이전 스타 수 이력이 필요하다(후속 과제).
    public void updateScore(Long repoId, double score) {
        redisTemplate.opsForZSet().add(TRENDING_REPOS_KEY, repoId.toString(), score);
    }

    public void increaseScore(Long repoId, double amount) {
        redisTemplate.opsForZSet().incrementScore(TRENDING_REPOS_KEY, repoId.toString(), amount);
    }

    public List<Long> getTopRepos(int count) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(TRENDING_REPOS_KEY, 0, count - 1);

        if (tuples == null) {
            return List.of();
        }

        return tuples.stream()
                .map(ZSetOperations.TypedTuple::getValue)
                .filter(member -> member != null)
                .map(this::parseRepoId)
                .filter(repoId -> repoId != null)
                .toList();
    }

    private Long parseRepoId(String member) {
        try {
            return Long.parseLong(member);
        } catch (NumberFormatException e) {
            log.warn("trending:repos에 저장된 member를 Long으로 변환하지 못했습니다. member={}", member, e);
            return null;
        }
    }
}
