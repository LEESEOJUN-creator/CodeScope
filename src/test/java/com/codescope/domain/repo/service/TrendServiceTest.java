package com.codescope.domain.repo.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TrendService의 Redis Sorted Set 기반 트렌드 랭킹 검증 테스트
 * 실제 Redis(kind Redis Pod, port-forward된 localhost:6379)에 연결해서,
 * increaseScore로 누적된 점수가 getTopRepos에서 내림차순으로 정확히
 * 정렬되는지, 점수 역전이 실제로 순위 역전으로 이어지는지 확인한다
 */
@SpringBootTest
class TrendServiceTest {

    private static final String TRENDING_REPOS_KEY = "trending:repos";

    private static final Long REPO_1 = 9001L;
    private static final Long REPO_2 = 9002L;
    private static final Long REPO_3 = 9003L;

    @Autowired
    private TrendService trendService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.opsForZSet().remove(
                TRENDING_REPOS_KEY,
                REPO_1.toString(), REPO_2.toString(), REPO_3.toString()
        );
    }

    @Test
    @DisplayName("increaseScore로 누적된 점수 순으로 getTopRepos가 내림차순 정렬해서 반환한다")
    void getTopRepos_점수_내림차순_정렬() {
        // given
        trendService.increaseScore(REPO_1, 100);
        trendService.increaseScore(REPO_2, 250);
        trendService.increaseScore(REPO_3, 80);

        // when
        List<Long> topRepos = trendService.getTopRepos(3);

        // then
        assertThat(topRepos).containsExactly(REPO_2, REPO_1, REPO_3);
    }

    @Test
    @DisplayName("increaseScore로 점수를 추가 누적하면 순위가 역전된다")
    void getTopRepos_점수_역전_시_순위도_역전() {
        // given
        trendService.increaseScore(REPO_1, 100);
        trendService.increaseScore(REPO_2, 250);
        trendService.increaseScore(REPO_3, 80);
        assertThat(trendService.getTopRepos(3)).containsExactly(REPO_2, REPO_1, REPO_3);

        // when
        trendService.increaseScore(REPO_3, 50); // 80 -> 130, REPO_1(100)을 추월

        // then
        assertThat(trendService.getTopRepos(3)).containsExactly(REPO_2, REPO_3, REPO_1);
    }
}
