package com.codescope.domain.user.entity;

import com.codescope.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "github_id", nullable = false, unique = true)
    private Long githubId;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Builder
    private User(Long githubId, String username, String email, String profileImageUrl) {
        this.githubId = githubId;
        this.username = username;
        this.email = email;
        this.profileImageUrl = profileImageUrl;
    }

    public static User of(Long githubId, String username, String email, String profileImageUrl) {
        return User.builder()
                .githubId(githubId)
                .username(username)
                .email(email)
                .profileImageUrl(profileImageUrl)
                .build();
    }

    public void update(String username, String email, String profileImageUrl) {
        this.username = username;
        this.email = email;
        this.profileImageUrl = profileImageUrl;
    }
}
