package com.codescope.domain.user.service;

import com.codescope.domain.user.entity.User;
import com.codescope.domain.user.repository.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserJpaRepository userJpaRepository;

    @Transactional
    public User findOrCreateByGithub(Long githubId, String email, String username, String profileImageUrl) {
        return userJpaRepository.findByGithubId(githubId)
                // TODO: 기존 User의 email/username/profileImageUrl이 GitHub 쪽과 달라졌을 때
                //  update()로 동기화할지는 현재 범위 밖 — 추후 결정
                .orElseGet(() -> createUser(githubId, email, username, profileImageUrl));
    }

    private User createUser(Long githubId, String email, String username, String profileImageUrl) {
        try {
            return userJpaRepository.save(User.of(githubId, username, email, profileImageUrl));
        } catch (DataIntegrityViolationException e) {
            log.warn("[UserService] githubId={} 동시 요청으로 인한 저장 충돌, 기존 User를 재조회합니다.", githubId);
            return userJpaRepository.findByGithubId(githubId)
                    .orElseThrow(() -> new IllegalStateException(
                            "githubId=" + githubId + "의 User 저장 충돌 후 재조회에도 실패했습니다.", e
                    ));
        }
    }
}
