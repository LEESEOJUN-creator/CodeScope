package com.codescope.api.controller.user;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.user.entity.User;
import com.codescope.domain.user.repository.UserFavoriteJpaRepository;
import com.codescope.domain.user.repository.UserJpaRepository;
import com.codescope.infra.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserFavorite API 통합 테스트.
 * 실제 요구사항: 인증된 사용자로 즐겨찾기 추가 → 조회 → 삭제가 실제로 동작하는지,
 * 그리고 사용자 A가 추가한 즐겨찾기가 사용자 B 목록에 섞이지 않는지 확인한다.
 *
 * 클래스 전체 @Transactional로 각 테스트가 끝나면 롤백되도록 해
 * 실제 k8s Postgres(공유 DB)에 테스트 데이터가 남지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserFavoriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Autowired
    private UserFavoriteJpaRepository userFavoriteJpaRepository;

    @Autowired
    private JwtProvider jwtProvider;

    private Long repoId;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        User userA = userJpaRepository.save(
                User.of(910001L, "user-a", "user-a@test.com", null));
        User userB = userJpaRepository.save(
                User.of(910002L, "user-b", "user-b@test.com", null));

        GithubRepository repo = githubRepositoryJpaRepository.save(
                GithubRepository.builder()
                        .name("test-repo")
                        .fullName("codescope-test/test-repo-" + System.nanoTime())
                        .description("UserFavorite 테스트용 픽스처")
                        .language("Java")
                        .starCount(100)
                        .forkCount(10)
                        .openIssueCount(2)
                        .githubUrl("https://github.com/codescope-test/test-repo")
                        .build());
        repoId = repo.getId();

        tokenA = jwtProvider.createAccessToken(userA.getUserId());
        tokenB = jwtProvider.createAccessToken(userB.getUserId());
    }

    @Test
    @DisplayName("즐겨찾기 추가 → 조회 → 삭제 흐름이 정상 동작하고, 다른 사용자 목록에는 섞이지 않는다")
    void favorite_추가_조회_삭제_사용자간_격리() throws Exception {
        // 1) 사용자 A가 즐겨찾기 추가
        mockMvc.perform(post("/api/users/me/bookmarks/{repoId}", repoId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isCreated());

        // 2) 사용자 A 목록에 보인다
        mockMvc.perform(get("/api/users/me/bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].repoId").value(repoId))
                .andExpect(jsonPath("$.data[0].favoritedAt").exists());

        // 3) 사용자 B 목록에는 안 보인다 (격리 확인)
        mockMvc.perform(get("/api/users/me/bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 4) 같은 레포 중복 즐겨찾기 시도 → 400
        mockMvc.perform(post("/api/users/me/bookmarks/{repoId}", repoId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isBadRequest());

        // 5) 사용자 A가 즐겨찾기 해제
        mockMvc.perform(delete("/api/users/me/bookmarks/{repoId}", repoId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // 6) 해제 후 목록에서 사라진다
        mockMvc.perform(get("/api/users/me/bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 7) 이미 없는 즐겨찾기를 다시 삭제 시도 → 404 (deleteBy 파생 메서드였다면 조용히 204가 나왔을 상황)
        mockMvc.perform(delete("/api/users/me/bookmarks/{repoId}", repoId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("인증 헤더 없이 요청하면 401을 반환한다")
    void 인증_없이_요청하면_401() throws Exception {
        mockMvc.perform(get("/api/users/me/bookmarks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("사용자 A가 사용자 B의 즐겨찾기를 삭제하려 하면 404를 반환하고, B의 즐겨찾기는 그대로 남는다")
    void 다른_사용자의_즐겨찾기_삭제_시도는_404로_막힌다() throws Exception {
        // given: 사용자 B가 즐겨찾기 추가
        mockMvc.perform(post("/api/users/me/bookmarks/{repoId}", repoId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isCreated());

        // when: 사용자 A가 같은 repoId를 삭제 시도
        // removeFavorite이 findByUserUserIdAndGithubRepositoryId(A, repoId)로 조회하므로
        // B 소유 즐겨찾기는 A 기준 조회에 걸리지 않아 "없음"(404)으로 처리되어야 한다
        mockMvc.perform(delete("/api/users/me/bookmarks/{repoId}", repoId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("즐겨찾기를 찾을 수 없습니다. repoId=" + repoId));

        // then: B의 즐겨찾기는 삭제되지 않고 그대로 남아있다
        mockMvc.perform(get("/api/users/me/bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].repoId").value(repoId));
    }
}
