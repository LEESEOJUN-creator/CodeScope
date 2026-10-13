package com.codescope.domain.repo.dto;

import java.util.List;

public record RepositoryEvidence(Long id, String fullName, String description, String language,
                                 int starCount, String readmeExcerpt, List<String> topics,
                                 List<String> matchedKeywords) {
    public RecommendedRepoDto candidate() {
        return new RecommendedRepoDto(id, fullName, description, language, starCount, topics, matchedKeywords,
                readmeExcerpt != null && !readmeExcerpt.isBlank());
    }
}
