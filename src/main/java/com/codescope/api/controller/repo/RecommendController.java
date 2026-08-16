package com.codescope.api.controller.repo;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.repo.dto.RepoRecommendResponse;
import com.codescope.domain.repo.service.RepoRecommendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Recommend", description = "AI 기반 레포 추천(RAG)")
@RestController
@RequiredArgsConstructor
public class RecommendController {

    private final RepoRecommendService repoRecommendService;

    // stack은 더 이상 필수가 아님 — 없으면 Service가 로그인 사용자의 저장된 관심
    // 스택을 자동 사용한다(우선순위 판단은 RepoRecommendService.resolveStack 참고).
    // userId는 이 경로가 permitAll이라 비로그인 시 자연스럽게 null로 들어온다.
    @Operation(summary = "기술 스택 기반 레포 추천 (pgvector 유사도 검색 + LLM, 로그인 시 저장된 관심 스택 자동 사용)")
    @GetMapping("/api/recommend")
    public ResponseEntity<ApiResponse<RepoRecommendResponse>> recommend(
            @RequestParam(required = false) String stack,
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.success(repoRecommendService.recommend(stack, userId)));
    }
}
