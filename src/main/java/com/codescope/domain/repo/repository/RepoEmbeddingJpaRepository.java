package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.RepoEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoEmbeddingJpaRepository extends JpaRepository<RepoEmbedding, Long> {
    Optional<RepoEmbedding> findByRepositoryId(Long repoId);
}
