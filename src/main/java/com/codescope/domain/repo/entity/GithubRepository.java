package com.codescope.domain.repo.entity;

import com.codescope.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Entity
@Table(name = "github_repository")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GithubRepository extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "github_repository_id")  
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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "repo_topic",
            joinColumns = @JoinColumn(name = "repo_id"),
            inverseJoinColumns = @JoinColumn(name = "topic_id")
    )
    private List<Topic> topics = new ArrayList<>();

    @Builder
    public GithubRepository(String name, String fullName, String description,
                            String language, int starCount, int forkCount,
                            int openIssueCount, String githubUrl) {
        this.name = name;
        this.fullName = fullName;
        this.description = description;
        this.language = language;
        this.starCount = starCount;
        this.forkCount = forkCount;
        this.openIssueCount = openIssueCount;
        this.githubUrl = githubUrl;
    }

    public List<String> getTopicNames() {
        return topics.stream().map(Topic::getName).toList();
    }

    public void updateTopics(List<Topic> newTopics) {
        this.topics.clear();
        this.topics.addAll(newTopics);
    }

    public void update(int starCount, int forkCount, int openIssueCount) {
        this.starCount = starCount;
        this.forkCount = forkCount;
        this.openIssueCount = openIssueCount;
    }
}