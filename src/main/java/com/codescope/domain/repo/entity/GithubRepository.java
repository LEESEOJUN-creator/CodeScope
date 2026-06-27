package com.codescope.domain.repo.entity;

import com.codescope.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Table(name = "github_repository")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GithubRepository extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;                // spring-boot

    @Column(nullable = false, unique = true)
    private String fullName;            // spring-projects/spring-boot

    @Column(columnDefinition = "TEXT")
    private String description;

    private String language;

    private int starCount;

    private int forkCount;

    private int openIssueCount;

    @Column(nullable = false)
    private String githubUrl;

    @ElementCollection
    @CollectionTable(
            name = "github_repository_topic",
            joinColumns = @JoinColumn(name = "repository_id")
    )
    @Column(name = "topic")
    private List<String> topics = new ArrayList<>();

    @Builder
    public GithubRepository(String name, String fullName, String description,
                            String language, int starCount, int forkCount,
                            int openIssueCount, String githubUrl, List<String> topics) {
        this.name = name;
        this.fullName = fullName;
        this.description = description;
        this.language = language;
        this.starCount = starCount;
        this.forkCount = forkCount;
        this.openIssueCount = openIssueCount;
        this.githubUrl = githubUrl;
        if (topics != null) this.topics = topics;
    }

    // 분석 결과 업데이트 (더티 체킹 활용)
    public void update(int starCount, int forkCount, int openIssueCount) {
        this.starCount = starCount;
        this.forkCount = forkCount;
        this.openIssueCount = openIssueCount;
    }
}