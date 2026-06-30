package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.Topic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TopicJpaRepository extends JpaRepository<Topic, Long> {
    Optional<Topic> findByName(String name);
}
