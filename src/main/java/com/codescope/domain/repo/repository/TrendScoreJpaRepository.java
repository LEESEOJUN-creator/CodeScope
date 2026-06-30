package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.TrendScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrendScoreJpaRepository extends JpaRepository<TrendScore, Long> {
    Optional<TrendScore> findByRepositoryId(Long repoId);
}
