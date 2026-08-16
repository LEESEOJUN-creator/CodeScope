package com.codescope.domain.user.service;

import com.codescope.domain.repo.entity.Topic;
import com.codescope.domain.repo.repository.TopicJpaRepository;
import com.codescope.domain.user.dto.SkillResponse;
import com.codescope.domain.user.entity.User;
import com.codescope.domain.user.entity.UserSkill;
import com.codescope.domain.user.repository.UserJpaRepository;
import com.codescope.domain.user.repository.UserSkillJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// UserFavoriteService와 동일 패턴. 여기 저장된 Topic 목록이
// "로그인 시 저장된 스택 자동 사용"(GET /api/recommend) 추천 쿼리의 데이터 소스가 될 예정
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserSkillService {

    private final UserSkillJpaRepository userSkillJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final TopicJpaRepository topicJpaRepository;

    @Transactional
    public void addSkill(Long userId, Long topicId) {
        if (userSkillJpaRepository.existsByUserUserIdAndTopicId(userId, topicId)) {
            throw new IllegalArgumentException("이미 등록된 관심 스택입니다. topicId=" + topicId);
        }

        User user = userJpaRepository.getReferenceById(userId);
        Topic topic = topicJpaRepository.findById(topicId)
                .orElseThrow(() -> new EntityNotFoundException("토픽을 찾을 수 없습니다. id=" + topicId));

        try {
            userSkillJpaRepository.save(UserSkill.of(user, topic));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 등록된 관심 스택입니다. topicId=" + topicId);
        }
    }

    // 조회 조건에 userId를 포함시켜, 다른 사용자의 데이터는 애초에 조회 결과에
    // 포함되지 않도록 함. 이는 별도의 소유자 검증 단계가 아니라 쿼리 조건 자체로
    // 접근 범위를 제한하는 방식(row-level 접근 제어)임 (UserFavoriteService와 동일 근거)
    public List<SkillResponse> getSkills(Long userId) {
        return userSkillJpaRepository.findAllByUserIdWithTopic(userId).stream()
                .map(SkillResponse::from)
                .toList();
    }
}
