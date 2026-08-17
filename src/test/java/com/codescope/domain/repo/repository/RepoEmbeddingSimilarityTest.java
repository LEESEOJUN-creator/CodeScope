package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.RepoEmbedding;
import com.codescope.support.PostgresTestContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector 저장·검색 자체를 검증하는 테스트.
 *
 * 실제 임베딩 모델(Ollama nomic-embed-text)은 사용하지 않는다.
 * 목적은 "직접 만든 벡터를 넣었을 때 pgvector의 <=> 코사인 거리 연산이
 * 기대한 순서로 정렬해 돌려주는가"만 확인하는 것이다. 실제 임베딩
 * 모델을 연동한 뒤에도 이 테스트는 pgvector 인프라 자체의 회귀 검증용으로
 * 재사용 가능하다.
 *
 * 벡터 3개(768차원, nomic-embed-text 차원에 맞춤) 설계:
 *   - A(쿼리 벡터): 앞쪽 절반이 1, 뒤쪽 절반이 0
 *   - B(A와 가깝게): A와 거의 같은 방향, 아주 약간만 다름
 *   - C(A와 멀게): A와 정반대 방향(뒤쪽 절반이 1, 앞쪽 절반이 0)
 * A로 검색하면 코사인 거리상 B가 C보다 먼저 나와야 한다.
 */
@DataJpaTest
@Sql(statements = "SET client_min_messages TO WARNING")
class RepoEmbeddingSimilarityTest extends PostgresTestContainer {

    private static final int DIM = 768;

    @Autowired
    private RepoEmbeddingJpaRepository repoEmbeddingJpaRepository;

    @Autowired
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Test
    @DisplayName("코사인 거리(<=>) 기준 검색 시 쿼리 벡터와 가까운 벡터가 먼저 나온다")
    void findNearestByEmbedding_returnsClosestVectorFirst() {
        // given
        float[] vectorA = buildVector(1f, 0f); // 쿼리 벡터
        float[] vectorB = buildVector(0.98f, 0.02f); // A와 가깝게 설계
        float[] vectorC = buildVector(0f, 1f); // A와 멀게(정반대) 설계

        GithubRepository repoA = saveRepository("owner/repo-a");
        GithubRepository repoB = saveRepository("owner/repo-b");
        GithubRepository repoC = saveRepository("owner/repo-c");

        repoEmbeddingJpaRepository.save(RepoEmbedding.of(repoA, vectorA));
        repoEmbeddingJpaRepository.save(RepoEmbedding.of(repoB, vectorB));
        repoEmbeddingJpaRepository.save(RepoEmbedding.of(repoC, vectorC));

        // when
        List<RepoEmbedding> result = repoEmbeddingJpaRepository
                .findNearestByEmbedding(toVectorLiteral(vectorA), 10);

        // then
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getRepository().getFullName()).isEqualTo("owner/repo-a"); // 자기 자신, 거리 0
        assertThat(result.get(1).getRepository().getFullName()).isEqualTo("owner/repo-b"); // 가까운 벡터
        assertThat(result.get(2).getRepository().getFullName()).isEqualTo("owner/repo-c"); // 먼 벡터
    }

    private GithubRepository saveRepository(String fullName) {
        GithubRepository repository = GithubRepository.builder()
                .name(fullName.split("/")[1])
                .fullName(fullName)
                .description("pgvector 유사도 테스트용 더미 레포")
                .language("Java")
                .starCount(0)
                .forkCount(0)
                .openIssueCount(0)
                .githubUrl("https://github.com/" + fullName)
                .build();
        return githubRepositoryJpaRepository.save(repository);
    }

    // front가 앞쪽 절반, back이 뒤쪽 절반을 채우는 768차원 벡터를 만든다.
    private float[] buildVector(float front, float back) {
        float[] vector = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            vector[i] = i < DIM / 2 ? front : back;
        }
        return vector;
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        return sb.append("]").toString();
    }
}
