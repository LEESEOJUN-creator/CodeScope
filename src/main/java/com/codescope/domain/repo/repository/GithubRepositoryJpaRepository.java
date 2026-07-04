package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.GithubRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GithubRepositoryJpaRepository extends JpaRepository<GithubRepository, Long> {

    // fullName으로 조회 (중복 저장 방지용)
    Optional<GithubRepository> findByFullName(String fullName);

    // 언어별 조회
    List<GithubRepository> findByLanguageOrderByStarCountDesc(String language);

    // 존재 여부 확인
    boolean existsByFullName(String fullName);

    // topics fetch join (N+1 방지, 페이징 없음)
    @Query("SELECT r FROM GithubRepository r JOIN FETCH r.topics")
    List<GithubRepository> findAllWithTopics();
}