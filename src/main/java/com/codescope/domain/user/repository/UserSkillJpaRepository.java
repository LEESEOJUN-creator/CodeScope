package com.codescope.domain.user.repository;

import com.codescope.domain.user.entity.UserSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserSkillJpaRepository extends JpaRepository<UserSkill, Long> {

    boolean existsByUserUserIdAndTopicId(Long userId, Long topicId);

    // topic fetch join으로 N+1 방지. ORDER BY 없이는 반환 순서가 보장되지 않아
    // (GET /api/recommend가 이 목록을 쉼표로 이어붙여 검색 질의를 구성하므로
    // 순서가 흔들리면 같은 사용자도 매번 다른 질의 문자열이 만들어짐) 등록 순으로 고정
    @Query("SELECT s FROM UserSkill s JOIN FETCH s.topic WHERE s.user.userId = :userId ORDER BY s.userSkillId")
    List<UserSkill> findAllByUserIdWithTopic(@Param("userId") Long userId);
}
