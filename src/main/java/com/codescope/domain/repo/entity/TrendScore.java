package com.codescope.domain.repo.entity;

import com.codescope.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trend_scores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrendScore extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trend_score_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false, unique = true)
    private GithubRepository repository;

    @Column(nullable = false)
    private Double score;

    @Column(nullable = false)
    private LocalDateTime calculatedAt;

    public static TrendScore of(GithubRepository repository, Double score) {
        TrendScore trendScore = new TrendScore();
        trendScore.repository = repository;
        trendScore.score = score;
        trendScore.calculatedAt = LocalDateTime.now();
        return trendScore;
    }

    public void updateScore(Double newScore) {
        this.score = newScore;
        this.calculatedAt = LocalDateTime.now();
    }
}
