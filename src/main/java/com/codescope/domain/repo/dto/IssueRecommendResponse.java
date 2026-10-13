package com.codescope.domain.repo.dto;
import java.time.Instant;
import java.util.List;

public record IssueRecommendResponse(String skills, String recommendation, List<IssueCandidate> issues,
                                     List<String> searchedRepositories, boolean incompleteResults, Instant checkedAt) {
    public record IssueCandidate(long id, int number, String title, String url, String updatedAt) {}
}
