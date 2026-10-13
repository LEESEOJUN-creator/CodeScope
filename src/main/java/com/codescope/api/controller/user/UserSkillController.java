package com.codescope.api.controller.user;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.user.dto.SkillRequest;
import com.codescope.domain.user.dto.SkillResponse;
import com.codescope.domain.user.service.UserSkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 여기 저장된 스택은 로그인 사용자가 stack 파라미터 없이 GET /api/recommend를
// 호출할 때 자동으로 추천 질의가 된다(RepoRecommendService.resolveStack).
@Tag(name = "UserSkill", description = "관심 기술 스택")
@RestController
@RequestMapping("/api/users/me/skills")
@RequiredArgsConstructor
public class UserSkillController {

    private final UserSkillService userSkillService;

    @Operation(summary = "관심 스택 목록 (등록 순)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<SkillResponse>>> getSkills(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.success(userSkillService.getSkills(userId)));
    }

    @Operation(summary = "관심 스택 추가 (이름으로, 없는 토픽이면 생성)")
    @PostMapping
    public ResponseEntity<ApiResponse<SkillResponse>> addSkill(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody SkillRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(userSkillService.addSkillByName(userId, request.name())));
    }

    @Operation(summary = "관심 스택 삭제")
    @DeleteMapping("/{topicId}")
    public ResponseEntity<Void> removeSkill(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long topicId
    ) {
        userSkillService.removeSkill(userId, topicId);
        return ResponseEntity.noContent().build();
    }
}
