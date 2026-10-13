package com.codescope.api.controller.repo;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.repo.dto.IssueRecommendResponse;
import com.codescope.domain.repo.repository.DiscoveryRepository;
import com.codescope.domain.repo.service.IssueRecommendService;
import com.codescope.domain.repo.service.StackKeywords;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class DiscoveryController {
    private final DiscoveryRepository discovery;
    private final StackKeywords keywords;
    private final IssueRecommendService issues;

    @GetMapping("/api/keywords")
    public ApiResponse<List<DiscoveryRepository.Keyword>> keywords() {
        return ApiResponse.success(discovery.keywords());
    }

    @GetMapping("/api/ecosystem")
    public ApiResponse<DiscoveryRepository.Ecosystem> ecosystem(@RequestParam String tech) {
        var terms = keywords.parse(tech);
        if (terms.size() != 1) throw new IllegalArgumentException("기술 생태계는 한 번에 기술 하나를 조회해주세요.");
        return ApiResponse.success(discovery.ecosystem(terms.getFirst()));
    }

    @GetMapping("/api/issues/recommend")
    public ApiResponse<IssueRecommendResponse> issues(@RequestParam String skills) {
        return ApiResponse.success(issues.recommend(skills));
    }
}
