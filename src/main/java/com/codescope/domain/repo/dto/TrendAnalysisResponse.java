package com.codescope.domain.repo.dto;

public record TrendAnalysisResponse(
        Long repoId,
        String fullName,
        String analysis
) {
}
