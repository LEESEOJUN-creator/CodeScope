package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.*;
import com.codescope.domain.repo.service.RepositoryTopicService;
import com.codescope.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
class DiscoveryRepositoryTest extends PostgresTestContainer {
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired GithubRepositoryJpaRepository repositories;
    @Autowired RepoEmbeddingJpaRepository embeddings;
    @Autowired TopicJpaRepository topics;
    DiscoveryRepository discovery;

    @BeforeEach void setUp() { discovery = new DiscoveryRepository(jdbc, new ObjectMapper()); }

    @Test void exactLanguageWinsOverCloserVectorAndFailedRowsAreExcluded() {
        var js = seed("test/js", "JavaScript", vector(1, 0), true);
        var java = seed("test/java", "Java", vector(0, 1), true);
        seed("test/failed", "Java", vector(1, 0), false);
        em.flush();
        var result = discovery.search(new PGvector(vector(1, 0)).toString(), List.of("java"), 10);
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().id()).isEqualTo(java.getId());
        assertThat(result.getFirst().matchedKeywords()).containsExactly("java");
        assertThat(result.get(1).id()).isEqualTo(js.getId());
        assertThat(result.get(1).matchedKeywords()).isEmpty();
    }

    @Test void topicsArePersistedUsedAsEvidenceAndCountedWithoutUserOnlyKeywords() {
        var repo = seed("test/service", "Java", vector(1, 0), true);
        em.flush();
        new RepositoryTopicService(repositories, topics, jdbc).sync(repo.getId(), List.of("spring-boot", "kafka", "kafka"));
        topics.save(Topic.of("user-only"));
        em.flush();
        var result = discovery.search(new PGvector(vector(1, 0)).toString(), List.of("spring-boot"), 10);
        assertThat(result.getFirst().matchedKeywords()).containsExactly("spring-boot");
        assertThat(result.getFirst().topics()).containsExactly("kafka", "spring-boot");
        assertThat(result.getFirst().readmeExcerpt()).isEqualTo("Evidence");
        assertThat(discovery.keywords()).extracting(DiscoveryRepository.Keyword::name)
                .contains("java", "spring-boot", "kafka").doesNotContain("user-only");
        var ecosystem = discovery.ecosystem("spring-boot");
        assertThat(ecosystem.repositoryCount()).isEqualTo(1);
        assertThat(ecosystem.related()).extracting(DiscoveryRepository.RelatedTechnology::name)
                .containsExactly("java", "kafka");
        assertThat(discovery.ecosystem("jev").repositoryCount()).isZero();
        // 빈 목록으로 재수집하면 과거의 토픽 연결을 제거한다.
        new RepositoryTopicService(repositories, topics, jdbc).sync(repo.getId(), List.of());
        em.flush();
        assertThat(discovery.ecosystem("spring-boot").repositoryCount()).isZero();
    }

    private GithubRepository seed(String name, String language, float[] vector, boolean embedded) {
        var repo = GithubRepository.builder().name(name.split("/")[1]).fullName(name)
                .language(language).githubUrl("https://github.com/" + name).build();
        repo.updateReadmeExcerpt("Evidence");
        if (embedded) repo.markEmbedded(); else repo.markFailed();
        repositories.save(repo);
        embeddings.save(RepoEmbedding.of(repo, vector));
        return repo;
    }
    private float[] vector(float x, float y) {
        var result = new float[768]; result[0]=x; result[1]=y; return result;
    }
}
