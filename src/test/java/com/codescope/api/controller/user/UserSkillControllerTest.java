package com.codescope.api.controller.user;

import com.codescope.domain.user.entity.User;
import com.codescope.domain.user.repository.UserJpaRepository;
import com.codescope.infra.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/users/me + 관심 스택(/api/users/me/skills) API 통합 테스트.
 * UserFavoriteControllerTest와 같은 이유로 클래스 전체 @Transactional(롤백).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserSkillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JwtProvider jwtProvider;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        User userA = userJpaRepository.save(User.of(930001L, "skill-user-a", "skill-a@test.com", "https://avatars/a.png"));
        User userB = userJpaRepository.save(User.of(930002L, "skill-user-b", "skill-b@test.com", null));
        tokenA = jwtProvider.createAccessToken(userA.getUserId());
        tokenB = jwtProvider.createAccessToken(userB.getUserId());
    }

    @Test
    @DisplayName("GET /api/users/me는 로그인 사용자 정보를, 비로그인은 401을 돌려준다")
    void me() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("skill-user-a"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://avatars/a.png"));

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("이름으로 스택 추가 → 목록 조회 → 삭제. 대소문자만 다른 이름은 같은 토픽으로 보고 중복 거절")
    void addListRemove() throws Exception {
        String unique = "skilltest-" + System.nanoTime();

        String body = mockMvc.perform(post("/api/users/me/skills")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  " + unique + "  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.topicName").value(unique))
                .andReturn().getResponse().getContentAsString();
        long topicId = Long.parseLong(body.replaceAll(".*\"topicId\":(\\d+).*", "$1"));

        mockMvc.perform(post("/api/users/me/skills")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + unique.toUpperCase() + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/users/me/skills").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].topicId").value(topicId));

        // 다른 사용자는 A의 스택을 지울 수 없다
        mockMvc.perform(delete("/api/users/me/skills/" + topicId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/users/me/skills/" + topicId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me/skills").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("빈 이름은 400")
    void blankName() throws Exception {
        mockMvc.perform(post("/api/users/me/skills")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }
}
