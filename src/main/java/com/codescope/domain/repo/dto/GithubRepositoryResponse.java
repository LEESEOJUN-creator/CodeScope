package com.codescope.domain.repo.dto;

import com.codescope.domain.repo.entity.GithubRepository;

import java.time.LocalDateTime;
import java.util.List;

// 왜 @Builder 클래스에서 record로 바꿨는가: 이 DTO가 popularRepos 캐시(Redis,
// GithubRepositoryService.getById)의 @Cacheable 대상인데, Lombok @Builder만
// 있는 클래스는 Jackson이 역직렬화할 공개 생성자가 없어 캐시에서 다시 읽을 때
// "no Creators, like default constructor, exist"로 500이 났다. record는 공개
// 정식 생성자가 있어 그대로 재구성 가능 — CLAUDE.md 컨벤션과도 맞는 방향.
public record GithubRepositoryResponse(
        Long id,
        String name,
        String fullName,
        String description,
        String language,
        int starCount,
        int forkCount,
        int openIssueCount,
        String githubUrl,
        List<String> topics,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static GithubRepositoryResponse from(GithubRepository entity) {
        return new GithubRepositoryResponse(
                entity.getId(),
                entity.getName(),
                entity.getFullName(),
                entity.getDescription(),
                entity.getLanguage(),
                entity.getStarCount(),
                entity.getForkCount(),
                entity.getOpenIssueCount(),
                entity.getGithubUrl(),
                entity.getTopicNames(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
