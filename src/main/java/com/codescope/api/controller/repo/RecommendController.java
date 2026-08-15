package com.codescope.api.controller.repo;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.repo.dto.RepoRecommendResponse;
import com.codescope.domain.repo.service.RepoRecommendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Recommend", description = "AI 기반 레포 추천(RAG)")
@RestController
@RequiredArgsConstructor
public class RecommendController {

    private final RepoRecommendService repoRecommendService;

    @Operation(summary = "기술 스택 기반 레포 추천 (pgvector 유사도 검색 + LLM)")
    @GetMapping("/api/recommend")
    public ResponseEntity<ApiResponse<RepoRecommendResponse>> recommend(@RequestParam String stack) {
        return ResponseEntity.ok(ApiResponse.success(repoRecommendService.recommend(stack)));
    }
}
