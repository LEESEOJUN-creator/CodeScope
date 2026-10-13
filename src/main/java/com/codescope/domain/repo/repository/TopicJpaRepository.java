package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.Topic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TopicJpaRepository extends JpaRepository<Topic, Long> {
    Optional<Topic> findByName(String name);

    // 관심 스택 입력("java"/"Java")이 대소문자만 다른 토픽을 따로 만들지 않도록
    Optional<Topic> findFirstByNameIgnoreCase(String name);
}
