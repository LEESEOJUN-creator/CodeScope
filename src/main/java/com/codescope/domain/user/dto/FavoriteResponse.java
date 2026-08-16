package com.codescope.domain.user.dto;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.user.entity.UserFavorite;

import java.time.LocalDateTime;

public record FavoriteResponse(
        Long repoId,
        String fullName,
        String description,
        String language,
        int starCount,
        String githubUrl,
        // UserFavorite.createdAt = "언제 즐겨찾기했는지" — BaseEntity의 범용 감사 필드가 아니라
        // 이 화면에서 실제로 의미를 갖는 값이라 별도 이름(favoritedAt)으로 노출
        LocalDateTime favoritedAt
) {
    public static FavoriteResponse from(UserFavorite favorite) {
        GithubRepository repo = favorite.getGithubRepository();
        return new FavoriteResponse(
                repo.getId(),
                repo.getFullName(),
                repo.getDescription(),
                repo.getLanguage(),
                repo.getStarCount(),
                repo.getGithubUrl(),
                favorite.getCreatedAt()
        );
    }
}
