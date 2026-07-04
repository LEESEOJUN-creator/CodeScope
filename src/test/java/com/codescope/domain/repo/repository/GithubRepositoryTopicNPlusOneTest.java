package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.Topic;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GithubRepository - Topic (@ManyToMany, LAZY) N+1 재현 테스트
 * 해결 로직(fetch join, @EntityGraph 등)은 의도적으로 넣지 않음
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GithubRepositoryTopicNPlusOneTest {

    @Autowired
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Autowired
    private TopicJpaRepository topicJpaRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("findAll 후 각 repository의 topics를 순회하면 N+1 쿼리가 발생한다")
    void findAll_후_topics_순회시_N플러스1_발생() {
        // given
        Topic spring = topicJpaRepository.save(Topic.of("spring"));
        Topic java = topicJpaRepository.save(Topic.of("java"));
        Topic jpa = topicJpaRepository.save(Topic.of("jpa"));
        Topic kafka = topicJpaRepository.save(Topic.of("kafka"));
        Topic redis = topicJpaRepository.save(Topic.of("redis"));

        List<List<Topic>> topicGroups = List.of(
                List.of(spring, java),
                List.of(java, jpa, kafka),
                List.of(kafka, redis),
                List.of(spring, jpa, redis),
                List.of(java, kafka)
        );

        for (int i = 0; i < topicGroups.size(); i++) {
            GithubRepository repository = GithubRepository.builder()
                    .name("repo-" + i)
                    .fullName("codescope-test/repo-" + i)
                    .description("N+1 테스트용 repository " + i)
                    .language("Java")
                    .starCount(0)
                    .forkCount(0)
                    .openIssueCount(0)
                    .githubUrl("https://github.com/codescope-test/repo-" + i)
                    .build();
            repository.updateTopics(topicGroups.get(i));
            githubRepositoryJpaRepository.save(repository);
        }

        entityManager.flush();  // 메모리에 남아있는 변경사항을 DB에 즉시 반영
        entityManager.clear();  // 영속성 컨텍스트(1차 캐시)를 완전히 비움

        // when
        List<GithubRepository> repositories = githubRepositoryJpaRepository.findAll();

        // then
        for (GithubRepository repository : repositories) {
            int topicCount = repository.getTopics().size();
            assertThat(topicCount).isGreaterThan(0);
        }
    }
}
