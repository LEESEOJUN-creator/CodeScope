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

    // V4: Day 26+27 TrendAnalysisService가 생성한 "왜 이 레포가 뜨는가"
    // LLM 분석 텍스트. score(정렬용 숫자)와 별개 개념이라 컬럼을 분리했다.
    @Column(columnDefinition = "TEXT")
    private String analysisText;

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

    public void updateAnalysisText(String analysisText) {
        this.analysisText = analysisText;
    }
}
