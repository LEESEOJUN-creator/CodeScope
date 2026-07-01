package com.codescope.domain.repo.entity;

import com.codescope.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "issue_id")
    private Long id;

    @Column(nullable = false)
    private Long githubIssueId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IssueState state;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    private GithubRepository repository;

    public enum IssueState {
        OPEN, CLOSED
    }

    public static Issue of(Long githubIssueId, String title, IssueState state, GithubRepository repository) {
        Issue issue = new Issue();
        issue.githubIssueId = githubIssueId;
        issue.title = title;
        issue.state = state;
        issue.repository = repository;
        return issue;
    }

    public void close() {
        this.state = IssueState.CLOSED;
    }

    public void reopen() {
        this.state = IssueState.OPEN;
    }
}
