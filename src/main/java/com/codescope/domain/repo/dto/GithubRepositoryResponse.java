package com.codescope.domain.repo.dto;

import com.codescope.domain.repo.entity.GithubRepository;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class GithubRepositoryResponse {

    private Long id;
    private String name;
    private String fullName;
    private String description;
    private String language;
    private int starCount;
    private int forkCount;
    private int openIssueCount;
    private String githubUrl;
    private List<String> topics;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static GithubRepositoryResponse from(GithubRepository entity) {
        return GithubRepositoryResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .fullName(entity.getFullName())
                .description(entity.getDescription())
                .language(entity.getLanguage())
                .starCount(entity.getStarCount())
                .forkCount(entity.getForkCount())
                .openIssueCount(entity.getOpenIssueCount())
                .githubUrl(entity.getGithubUrl())
                .topics(entity.getTopics())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
