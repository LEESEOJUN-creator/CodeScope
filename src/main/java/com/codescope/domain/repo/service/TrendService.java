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
