package com.codescope.domain.user.repository;

import com.codescope.domain.user.entity.UserSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserSkillJpaRepository extends JpaRepository<UserSkill, Long> {

    boolean existsByUserUserIdAndTopicId(Long userId, Long topicId);

    // topic fetch join으로 N+1 방지
    @Query("SELECT s FROM UserSkill s JOIN FETCH s.topic WHERE s.user.userId = :userId")
    List<UserSkill> findAllByUserIdWithTopic(@Param("userId") Long userId);
}
