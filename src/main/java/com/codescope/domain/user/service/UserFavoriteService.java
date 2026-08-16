package com.codescope.domain.user.service;

import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.user.dto.FavoriteResponse;
import com.codescope.domain.user.entity.User;
import com.codescope.domain.user.entity.UserFavorite;
import com.codescope.domain.user.repository.UserFavoriteJpaRepository;
import com.codescope.domain.user.repository.UserJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserFavoriteService {

    private final UserFavoriteJpaRepository userFavoriteJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Transactional
    public void addFavorite(Long userId, Long repoId) {
        if (userFavoriteJpaRepository.existsByUserUserIdAndGithubRepositoryId(userId, repoId)) {
            throw new IllegalArgumentException("이미 즐겨찾기한 레포지토리입니다. repoId=" + repoId);
        }

        // userId는 JwtAuthenticationFilter를 통과한 값이라 User 존재가 보장됨
        // → 실제 SELECT 없이 프록시만 얻는 getReferenceById로 불필요한 조회 생략
        User user = userJpaRepository.getReferenceById(userId);
        GithubRepository repo = githubRepositoryJpaRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("레포지토리를 찾을 수 없습니다. id=" + repoId));

        try {
            userFavoriteJpaRepository.save(UserFavorite.of(user, repo));
        } catch (DataIntegrityViolationException e) {
            // existsBy 체크와 save 사이의 동시 요청(TOCTOU)은 UNIQUE 제약(user_id, github_repository_id)이 최종 방어
            throw new IllegalArgumentException("이미 즐겨찾기한 레포지토리입니다. repoId=" + repoId);
        }
    }

    // 조회 조건에 userId를 포함시켜, 다른 사용자의 데이터는 애초에 조회 결과에
    // 포함되지 않도록 함. 이는 별도의 소유자 검증 단계가 아니라 쿼리 조건 자체로
    // 접근 범위를 제한하는 방식(row-level 접근 제어)임 — 다른 사용자의 즐겨찾기가
    // 이 목록에 섞일 수 없는 이유가 여기 있다.
    public List<FavoriteResponse> getFavorites(Long userId) {
        return userFavoriteJpaRepository.findAllByUserIdWithRepository(userId).stream()
                .map(FavoriteResponse::from)
                .toList();
    }

    @Transactional
    public void removeFavorite(Long userId, Long repoId) {
        // deleteBy 파생 메서드는 대상이 없어도 0건 처리로 조용히 성공해버려
        // "이미 없는 즐겨찾기를 삭제 시도했다"를 사용자에게 알릴 수 없음
        // → findBy로 먼저 조회해 없으면 404를 명시적으로 던진다
        //
        // 조회 조건에 userId를 포함시켜, 다른 사용자의 데이터는 애초에 조회 결과에
        // 포함되지 않도록 함. 이는 별도의 소유자 검증 단계가 아니라 쿼리 조건 자체로
        // 접근 범위를 제한하는 방식(row-level 접근 제어)이며, 타 사용자가 존재하지
        // 않는 리소스에 접근한 것과 동일하게 404로 응답해 데이터 존재 여부 자체를
        // 노출하지 않는 효과도 있음.
        UserFavorite favorite = userFavoriteJpaRepository.findByUserUserIdAndGithubRepositoryId(userId, repoId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "즐겨찾기를 찾을 수 없습니다. repoId=" + repoId));
        userFavoriteJpaRepository.delete(favorite);
    }
}
