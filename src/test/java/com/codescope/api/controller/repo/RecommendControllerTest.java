package com.codescope.api.controller.repo;

import com.codescope.client.llm.EmbeddingService;
import com.codescope.client.llm.LlmClient;
import com.codescope.domain.repo.entity.Topic;
import com.codescope.domain.repo.repository.TopicJpaRepository;
import com.codescope.domain.user.entity.User;
import com.codescope.domain.user.entity.UserSkill;
import com.codescope.domain.user.repository.UserJpaRepository;
import com.codescope.domain.user.repository.UserSkillJpaRepository;
import com.codescope.infra.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/recommend 로그인 연동 스택 결정 로직(RepoRecommendService.resolveStack) 검증.
 *
 * EmbeddingService/LlmClient를 @MockBean으로 대체해 실제 Ollama 없이도
 * "어떤 stack 문자열로 검색이 수행됐는지"만 확실히 검증한다 — pgvector
 * 유사도 결과/LLM 생성 품질은 RepoRecommendServiceIntegrationTest(실제
 * Ollama 필요, CI에서 reachability 체크로 스킵)의 관심사이고, 여기서는
 * "파라미터 우선순위 결정이 맞는가"만 확인하면 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecommendControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserSkillJpaRepository userSkillJpaRepository;

    @Autowired
    private TopicJpaRepository topicJpaRepository;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private LlmClient llmClient;

    private String token;

    @BeforeEach
    void setUp() {
        when(embeddingService.embedQuery(anyString())).thenReturn(new float[768]);
        when(llmClient.generate(anyString())).thenReturn("dummy-recommendation");

        User user = userJpaRepository.save(
                User.of(920001L, "recommend-user", "recommend-user@test.com", null));

        Topic java = topicJpaRepository.findByName("Java")
                .orElseGet(() -> topicJpaRepository.save(Topic.of("Java")));
        Topic kafka = topicJpaRepository.findByName("Kafka")
                .orElseGet(() -> topicJpaRepository.save(Topic.of("Kafka")));
        userSkillJpaRepository.save(UserSkill.of(user, java));
        userSkillJpaRepository.save(UserSkill.of(user, kafka));

        token = jwtProvider.createAccessToken(user.getUserId());
    }

    @Test
    @DisplayName("① stack 파라미터가 있으면 로그인 상태라도(저장된 UserSkill과 달라도) 파라미터 값이 그대로 쓰인다")
    void stack_파라미터가_명시되면_로그인_여부와_무관하게_그대로_사용() throws Exception {
        // 저장된 UserSkill(Java,Kafka)과 명백히 다른 값을 넘긴다
        mockMvc.perform(get("/api/recommend")
                        .param("stack", "Rust,WebAssembly")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stack").value("Rust,WebAssembly"));

        // 실제로 그 값으로 검색(임베딩 질의)됐는지까지 확인
        verify(embeddingService).embedQuery("Rust,WebAssembly");
    }

    @Test
    @DisplayName("② stack 파라미터 없이 로그인 + UserSkill 존재 시 저장된 관심 스택으로 자동 구성돼 검색된다")
    void stack_파라미터_없고_로그인_UserSkill_존재시_자동_구성() throws Exception {
        mockMvc.perform(get("/api/recommend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stack").value("Java,Kafka"));

        verify(embeddingService).embedQuery("Java,Kafka");
    }

    @Test
    @DisplayName("③ stack 파라미터 없고 비로그인이면 400을 반환한다")
    void stack_파라미터_없고_비로그인시_400() throws Exception {
        mockMvc.perform(get("/api/recommend"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "추천할 스택을 알 수 없습니다. stack 파라미터를 지정하거나 로그인 후 관심 스택을 등록해주세요."));
    }

    @Test
    @DisplayName("③-보조: stack 파라미터 없고 로그인은 했지만 UserSkill이 비어있어도 400을 반환한다")
    void stack_파라미터_없고_UserSkill_비어있으면_로그인해도_400() throws Exception {
        User userWithoutSkills = userJpaRepository.save(
                User.of(920002L, "no-skill-user", "no-skill@test.com", null));
        String tokenWithoutSkills = jwtProvider.createAccessToken(userWithoutSkills.getUserId());

        mockMvc.perform(get("/api/recommend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutSkills))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
