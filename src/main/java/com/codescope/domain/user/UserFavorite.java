package com.codescope.domain.user;

import com.codescope.common.entity.BaseEntity;
import com.codescope.domain.repo.entity.GithubRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_favorites", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "github_repository_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserFavorite extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_favorite_id")
    private Long userFavoriteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "github_repository_id", nullable = false)
    private GithubRepository githubRepository;

    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled;

    @Builder
    private UserFavorite(User user, GithubRepository githubRepository, boolean notificationEnabled) {
        this.user = user;
        this.githubRepository = githubRepository;
        this.notificationEnabled = notificationEnabled;
    }

    public static UserFavorite of(User user, GithubRepository githubRepository) {
        return UserFavorite.builder()
                .user(user)
                .githubRepository(githubRepository)
                .notificationEnabled(false)
                .build();
    }

    public void toggleNotification() {
        this.notificationEnabled = !this.notificationEnabled;
    }
}
