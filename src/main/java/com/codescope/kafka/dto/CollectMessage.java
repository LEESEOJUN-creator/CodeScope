package com.codescope.kafka.dto;

public record CollectMessage(
        String name,
        String fullName,
        String description,
        String language,
        int starCount,
        int forkCount,
        int openIssueCount,
        String githubUrl
) {
}
