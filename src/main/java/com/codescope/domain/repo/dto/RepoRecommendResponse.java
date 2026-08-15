package com.codescope.domain.repo.dto;

import java.util.List;

public record RepoRecommendResponse(
        String stack,
        String recommendation,
        List<RecommendedRepoDto> candidates
) {
}
