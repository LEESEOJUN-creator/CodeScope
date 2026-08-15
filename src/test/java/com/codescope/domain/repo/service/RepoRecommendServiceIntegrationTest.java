package com.codescope.domain.repo.service;

import com.codescope.client.llm.EmbeddingService;
import com.codescope.domain.repo.dto.RepoRecommendResponse;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.RepoEmbedding;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.repository.RepoEmbeddingJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RepoRecommendService(RAG) 통합 테스트 — 실제 Ollama(임베딩+생성)와
 * 실제 Postgres(pgvector)를 사용한다.
 *
 * 왜 @SpringBootTest인가: RepoRecommendService는 EmbeddingService(실제
 * Ollama 임베딩), RepoEmbeddingJpaRepository(실제 pgvector 검색),
 * LlmClient(실제 Ollama 생성)를 전부 실제 협력 객체로 써야 "pgvector
 * 검색 결과가 실제로 LLM 응답에 반영되는지"를 검증할 수 있다 —
 * DuplicateCheckServiceTest와 동일하게 이 프로젝트는 로컬 인프라(Postgres/
 * Redis/Kafka port-forward)가 떠 있다는 전제로 @SpringBootTest를 쓴다.
 *
 * 왜 Ollama reachability 체크로 CI 자동 스킵인가:
 * OllamaEmbeddingServiceIntegrationTest(Day 25)와 동일한 패턴 — CI에는
 * Ollama가 없으므로 11434 포트 연결을 먼저 시도해보고 실패하면
 * Assumptions.abort()로 스킵한다(빌드를 깨지 않음).
 */
@SpringBootTest
class RepoRecommendServiceIntegrationTest {

    private static final String STACK = "Java, Spring Boot, Kafka";

    @Autowired
    private RepoRecommendService repoRecommendService;

    @Autowired
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Autowired
    private RepoEmbeddingJpaRepository repoEmbeddingJpaRepository;

    @Autowired
    private EmbeddingService embeddingService;

    private GithubRepository relevantRepo;
    private GithubRepository irrelevantRepo;

    @BeforeAll
    static void checkOllamaReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 11434), 500);
        } catch (IOException e) {
            Assumptions.abort(
                    "로컬 Ollama(127.0.0.1:11434)에 연결할 수 없어 테스트를 스킵합니다: " + e.getMessage());
        }
    }

    @AfterEach
    void tearDown() {
        // FK 제약(repo_embeddings.repo_id → github_repository) 때문에
        // 자식(RepoEmbedding)을 먼저 지워야 부모를 지울 수 있다.
        if (relevantRepo != null) {
            repoEmbeddingJpaRepository.findByRepositoryId(relevantRepo.getId())
                    .ifPresent(repoEmbeddingJpaRepository::delete);
            githubRepositoryJpaRepository.delete(relevantRepo);
        }
        if (irrelevantRepo != null) {
            repoEmbeddingJpaRepository.findByRepositoryId(irrelevantRepo.getId())
                    .ifPresent(repoEmbeddingJpaRepository::delete);
            githubRepositoryJpaRepository.delete(irrelevantRepo);
        }
    }

    @Test
    @DisplayName("스택으로 추천을 요청하면 pgvector로 찾은 후보가 LLM 응답에 실제로 등장한다(환각 방지)")
    void recommend_includesActualCandidateRepoName() {
        // given: 스택과 밀접한 레포 1개 + 무관한 레포 1개를 실제 Ollama로 임베딩해 저장
        relevantRepo = seedEmbeddedRepo(
                "test-recommend/spring-kafka-toolkit",
                "Spring Boot와 Kafka로 만든 이벤트 기반 백엔드 툴킷. Java 21 가상 스레드 활용",
                "Java"
        );
        irrelevantRepo = seedEmbeddedRepo(
                "test-recommend/css-animation-playground",
                "순수 CSS로 만드는 애니메이션 실험 모음. 프론트엔드 전용",
                "CSS"
        );

        // when
        RepoRecommendResponse response = repoRecommendService.recommend(STACK);

        // then: 후보 목록에 두 레포 모두(가장 가까운 순으로) 포함
        assertThat(response.candidates())
                .extracting(c -> c.fullName())
                .contains(relevantRepo.getFullName(), irrelevantRepo.getFullName());

        // 환각 방지 핵심 검증: LLM 응답 텍스트에 실제 후보 목록에 있는
        // 레포 이름이 최소 1개는 등장해야 한다(완전히 무관한 이름을
        // 지어내지 않았다는 증거)
        assertThat(response.recommendation()).contains(relevantRepo.getFullName());
    }

    private GithubRepository seedEmbeddedRepo(String fullName, String description, String language) {
        GithubRepository repository = GithubRepository.builder()
                .name(fullName.split("/")[1])
                .fullName(fullName)
                .description(description)
                .language(language)
                .starCount(100)
                .forkCount(10)
                .openIssueCount(1)
                .githubUrl("https://github.com/" + fullName)
                .build();
        repository.markEmbedded();
        githubRepositoryJpaRepository.save(repository);

        float[] embedding = embeddingService.embed(fullName, description);
        repoEmbeddingJpaRepository.save(RepoEmbedding.of(repository, embedding));

        return repository;
    }
}
