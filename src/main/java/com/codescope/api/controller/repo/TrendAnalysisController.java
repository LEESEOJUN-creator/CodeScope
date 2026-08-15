package com.codescope.api.controller.repo;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.repo.dto.TrendAnalysisResponse;
import com.codescope.domain.repo.service.TrendAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "TrendAnalysis", description = "AI 기반 트렌드 분석(왜 뜨는가)")
@RestController
@RequiredArgsConstructor
public class TrendAnalysisController {

    private final TrendAnalysisService trendAnalysisService;

    @Operation(summary = "레포가 왜 뜨는지 LLM 분석 (Redis 1시간 캐싱)")
    @GetMapping("/api/trends/analysis")
    public ResponseEntity<ApiResponse<TrendAnalysisResponse>> analyze(@RequestParam Long repoId) {
        return ResponseEntity.ok(ApiResponse.success(trendAnalysisService.analyze(repoId)));
    }
}
