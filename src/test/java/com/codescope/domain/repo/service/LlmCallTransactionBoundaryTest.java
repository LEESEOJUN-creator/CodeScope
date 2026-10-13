package com.codescope.domain.repo.service;

import com.codescope.client.llm.EmbeddingService;
import com.codescope.client.llm.LlmClient;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.RepoEmbedding;
import com.codescope.domain.repo.entity.TrendScore;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.repository.RepoEmbeddingJpaRepository;
import com.codescope.domain.repo.repository.TrendScoreJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 호출이 DB 트랜잭션·커넥션 밖에서 일어나는지 검증하는 회귀 테스트.
 *
 * 배경: 추천/트렌드 분석 서비스가 클래스 레벨 @Transactional + OSIV(기본 on)
 *   상태에서 LLM 응답을 기다리는 동안 DB 커넥션을 점유해, 부하테스트 기준선에서
 *   HikariCP 풀 고갈(active 10 / pending 211)과 일반 조회 30초 타임아웃이
 *   발생했다. 누군가 서비스에 @Transactional을 다시 붙이거나 OSIV를 켜면 이
 *   테스트가 깨진다.
 *
 * 검증 방법: LlmClient mock이 호출되는 "그 순간", 호출 스레드에 트랜잭션이
 *   활성화돼 있는지와 EntityManager/JDBC 커넥션이 바인딩돼 있는지를 기록한다.
 *   OSIV는 웹 요청 단위로 걸리므로 서비스를 직접 부르지 않고 MockMvc로 실제
 *   HTTP 경로(OpenEntityManagerInViewInterceptor 포함)를 태운다.
 *
 * 왜 클래스에 @Transactional을 붙이지 않는가: 테스트 트랜잭션이 같은 스레드에
 *   걸려 있으면 그 자체가 "LLM 호출 중 트랜잭션 활성"으로 잡혀 검증이 무의미해진다.
 *   대신 @AfterEach에서 픽스처를 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LlmCallTransactionBoundaryTest {

    private static final float[] UNIT_VECTOR = unitVector();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Autowired
    private RepoEmbeddingJpaRepository repoEmbeddingJpaRepository;

    @Autowired
    private TrendScoreJpaRepository trendScoreJpaRepository;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private LlmClient llmClient;

    private final List<String> violations = new CopyOnWriteArrayList<>();
    private GithubRepository seeded;

    @BeforeEach
    void setUp() {
        when(embeddingService.embedQuery(anyString())).thenReturn(UNIT_VECTOR);
        when(llmClient.generate(anyString())).thenAnswer(invocation -> {
            recordBoundViolations();
            return "mock-llm-response";
        });

        seeded = GithubRepository.builder()
                .name("tx-boundary")
                .fullName("test-tx-boundary/tx-boundary")
                .description("LLM 호출 트랜잭션 경계 회귀 테스트용 픽스처")
                .language("Java")
                .starCount(1)
                .forkCount(0)
                .openIssueCount(0)
                .githubUrl("https://github.com/test-tx-boundary/tx-boundary")
                .build();
        seeded.markEmbedded();
        githubRepositoryJpaRepository.save(seeded);
        repoEmbeddingJpaRepository.save(RepoEmbedding.of(seeded, UNIT_VECTOR));
    }

    @AfterEach
    void tearDown() {
        Objects.requireNonNull(cacheManager.getCache("trendAnalysis")).evict(seeded.getId());
        trendScoreJpaRepository.findByRepositoryId(seeded.getId()).ifPresent(trendScoreJpaRepository::delete);
        repoEmbeddingJpaRepository.findByRepositoryId(seeded.getId()).ifPresent(repoEmbeddingJpaRepository::delete);
        githubRepositoryJpaRepository.deleteById(seeded.getId());
    }

    @Test
    @DisplayName("GET /api/recommend: LLM 호출 시점에 트랜잭션·EntityManager·커넥션이 스레드에 묶여 있지 않다")
    void recommend_callsLlmOutsideTransaction() throws Exception {
        mockMvc.perform(get("/api/recommend").param("stack", "Java, Spring Boot"))
                .andExpect(status().isOk());

        assertThat(violations).as("LLM 호출 중 스레드에 묶여 있던 DB 자원").isEmpty();
    }

    @Test
    @DisplayName("GET /api/trends/analysis: LLM 호출은 트랜잭션 밖에서, 결과는 upsert로 저장·갱신된다")
    void trendAnalysis_callsLlmOutsideTransaction_andUpserts() throws Exception {
        mockMvc.perform(get("/api/trends/analysis").param("repoId", seeded.getId().toString()))
                .andExpect(status().isOk());

        assertThat(violations).as("LLM 호출 중 스레드에 묶여 있던 DB 자원").isEmpty();
        assertThat(trendScoreJpaRepository.findByRepositoryId(seeded.getId()))
                .map(TrendScore::getAnalysisText)
                .hasValue("mock-llm-response");

        // 두 번째 분석(캐시 만료 상황)은 새 행이 아니라 기존 행을 갱신해야 한다
        // — uk_trend_scores_repo_id 위반 없이 upsert가 동작하는지 확인
        Objects.requireNonNull(cacheManager.getCache("trendAnalysis")).evict(seeded.getId());
        when(llmClient.generate(anyString())).thenReturn("updated-llm-response");

        mockMvc.perform(get("/api/trends/analysis").param("repoId", seeded.getId().toString()))
                .andExpect(status().isOk());

        assertThat(trendScoreJpaRepository.findByRepositoryId(seeded.getId()))
                .map(TrendScore::getAnalysisText)
                .hasValue("updated-llm-response");
    }

    private void recordBoundViolations() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            violations.add("활성 트랜잭션");
        }
        TransactionSynchronizationManager.getResourceMap().values().forEach(resource -> {
            if (resource instanceof EntityManagerHolder) {
                violations.add("EntityManager(OSIV 또는 트랜잭션)");
            }
            if (resource instanceof ConnectionHolder) {
                violations.add("JDBC 커넥션");
            }
        });
    }

    private static float[] unitVector() {
        float[] vector = new float[768];
        vector[0] = 1.0f;
        return vector;
    }
}
