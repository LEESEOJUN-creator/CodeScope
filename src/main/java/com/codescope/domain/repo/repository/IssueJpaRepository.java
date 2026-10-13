package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.Issue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 기존 저장 이슈 조회용. IssueRecommendService는 닫힌 이슈를 추천하지 않도록
// GitHub Search API에서 열린 이슈를 조회하며 이 테이블에 의존하지 않는다.
public interface IssueJpaRepository extends JpaRepository<Issue, Long> {
    List<Issue> findByRepositoryId(Long repoId);
}
