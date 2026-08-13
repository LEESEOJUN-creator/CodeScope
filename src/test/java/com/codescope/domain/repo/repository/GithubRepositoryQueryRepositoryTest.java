package com.codescope.domain.repo.repository;

import com.codescope.support.PostgresTestContainer;
import com.codescope.domain.repo.dto.SearchCondition;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.Topic;
import com.codescope.infra.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GithubRepositoryQueryRepository의 QueryDSL 동적 검색 검증 테스트
 * 조건 없음 / language만 / minStars만 / 여러 조건 조합에 대해 결과 개수를 확인한다
 */
@DataJpaTest
@Import({QuerydslConfig.class, GithubRepositoryQueryRepository.class})
class GithubRepositoryQueryRepositoryTest extends PostgresTestContainer {

    private static final Logger log = LoggerFactory.getLogger(GithubRepositoryQueryRepositoryTest.class);

    @Autowired
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Autowired
    private TopicJpaRepository topicJpaRepository;

    @Autowired
    private GithubRepositoryQueryRepository githubRepositoryQueryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private void setUpFixtures() {
        Topic javaTopic = topicJpaRepository.save(Topic.of("java"));
        Topic kafkaTopic = topicJpaRepository.save(Topic.of("kafka"));

        GithubRepository springBoot = GithubRepository.builder()
                .name("spring-boot")
                .fullName("spring-projects/spring-boot")
                .description("Spring Boot")
                .language("Java")
                .starCount(1000)
                .forkCount(100)
                .openIssueCount(10)
                .githubUrl("https://github.com/spring-projects/spring-boot")
                .build();
        springBoot.updateTopics(List.of(javaTopic));
        githubRepositoryJpaRepository.save(springBoot);

        GithubRepository kafkaRepo = GithubRepository.builder()
                .name("kafka")
                .fullName("apache/kafka")
                .description("Apache Kafka")
                .language("Java")
                .starCount(500)
                .forkCount(50)
                .openIssueCount(5)
                .githubUrl("https://github.com/apache/kafka")
                .build();
        kafkaRepo.updateTopics(List.of(kafkaTopic));
        githubRepositoryJpaRepository.save(kafkaRepo);

        GithubRepository redux = GithubRepository.builder()
                .name("redux")
                .fullName("reduxjs/redux")
                .description("Redux")
                .language("JavaScript")
                .starCount(100)
                .forkCount(10)
                .openIssueCount(1)
                .githubUrl("https://github.com/reduxjs/redux")
                .build();
        githubRepositoryJpaRepository.save(redux);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("검색 조건이 없으면 전체 레포지토리를 조회한다")
    void search_조건_없음_전체_조회() {
        // given
        setUpFixtures();
        SearchCondition condition = new SearchCondition(null, null, null, null);
        Pageable pageable = PageRequest.of(0, 10);

        // when
        log.info("===== 조건 없음 검색 시작 =====");
        Page<GithubRepository> result = githubRepositoryQueryRepository.search(condition, pageable);
        log.info("===== 조건 없음 검색 종료, 결과 건수={} =====", result.getTotalElements());

        // then
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("language 조건만 주면 해당 언어의 레포지토리만 조회한다")
    void search_language만() {
        // given
        setUpFixtures();
        SearchCondition condition = new SearchCondition(null, "Java", null, null);
        Pageable pageable = PageRequest.of(0, 10);

        // when
        log.info("===== language=Java 검색 시작 =====");
        Page<GithubRepository> result = githubRepositoryQueryRepository.search(condition, pageable);
        log.info("===== language=Java 검색 종료, 결과 건수={} =====", result.getTotalElements());

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).allMatch(repo -> "Java".equals(repo.getLanguage()));
    }

    @Test
    @DisplayName("minStars 조건만 주면 해당 star 이상인 레포지토리만 조회한다")
    void search_minStars만() {
        // given
        setUpFixtures();
        SearchCondition condition = new SearchCondition(null, null, null, 500);
        Pageable pageable = PageRequest.of(0, 10);

        // when
        log.info("===== minStars=500 검색 시작 =====");
        Page<GithubRepository> result = githubRepositoryQueryRepository.search(condition, pageable);
        log.info("===== minStars=500 검색 종료, 결과 건수={} =====", result.getTotalElements());

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).allMatch(repo -> repo.getStarCount() >= 500);
    }

    @Test
    @DisplayName("여러 조건을 조합하면 모든 조건을 만족하는 레포지토리만 조회한다")
    void search_여러_조건_조합() {
        // given
        setUpFixtures();
        SearchCondition condition = new SearchCondition("kafka", "Java", "kafka", 100);
        Pageable pageable = PageRequest.of(0, 10);

        // when
        log.info("===== 조합 조건 검색 시작 =====");
        Page<GithubRepository> result = githubRepositoryQueryRepository.search(condition, pageable);
        log.info("===== 조합 조건 검색 종료, 결과 건수={} =====", result.getTotalElements());

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getFullName()).isEqualTo("apache/kafka");
    }

    @Test
    @DisplayName("@BatchSize(size=100)이 적용되어 topics LAZY 로딩이 IN절 배치 쿼리 1번으로 처리된다")
    void search_결과_topics_LAZY_로딩_배치조회() {
        // given
        setUpFixtures();
        SearchCondition condition = new SearchCondition(null, null, null, null);
        Pageable pageable = PageRequest.of(0, 10);

        // when
        log.info("===== 배치 검증용 검색 시작 =====");
        Page<GithubRepository> result = githubRepositoryQueryRepository.search(condition, pageable);
        log.info("===== 배치 검증용 검색 종료, 결과 건수={} =====", result.getTotalElements());

        log.info("===== topics LAZY 로딩 순회 시작 (여기서부터 나가는 SQL이 레포당 1번이 아니라 IN절 배치 쿼리 1번인지 확인) =====");
        int totalTopicCount = 0;
        for (GithubRepository repository : result.getContent()) {
            totalTopicCount += repository.getTopics().size();
        }
        log.info("===== topics LAZY 로딩 순회 종료, 전체 topics 개수={} =====", totalTopicCount);

        // then
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(totalTopicCount).isEqualTo(2);
    }
}
