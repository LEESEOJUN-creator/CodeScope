package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.Issue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueJpaRepository extends JpaRepository<Issue, Long> {
    List<Issue> findByRepositoryId(Long repoId);
}
