package com.codescope.domain.repo.dto;

import com.codescope.domain.repo.entity.GithubRepository;

import java.time.LocalDateTime;
import java.util.List;

// 왜 @Builder 클래스에서 record로 바꿨는가(2026-08-16, GET /api/repos/{id} 500 조사):
//   이 DTO가 popularRepos 캐시(Redis, GithubRepositoryService.getById)의
//   @Cacheable 대상인데, Lombok @Builder만 있는 클래스는 Jackson이 역직렬화할
//   기본/공개 생성자가 없어(빌더 내부의 private all-args 생성자뿐) 캐시에서
//   다시 읽을 때 "no Creators, like default constructor, exist"로 500이 났다.
//   record는 공개 정식 생성자가 있어 Jackson이 그대로 재구성할 수 있다 —
//   CLAUDE.md 컨벤션("DTO는 record 사용")과도 맞는 방향이라 이번 기회에 맞췄다.
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
