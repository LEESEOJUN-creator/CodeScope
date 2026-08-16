package com.codescope.domain.user.repository;

import com.codescope.domain.user.entity.UserFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserFavoriteJpaRepository extends JpaRepository<UserFavorite, Long> {

    // 저장 전 중복 체크 (UNIQUE 제약은 최종 방어선, 이건 친절한 에러 메시지용 1차 체크)
    boolean existsByUserUserIdAndGithubRepositoryId(Long userId, Long repoId);

    // 삭제 대상 조회용 — 없으면 404로 명확히 응답하기 위해 deleteBy 파생 메서드 대신 findBy 사용
    Optional<UserFavorite> findByUserUserIdAndGithubRepositoryId(Long userId, Long repoId);

    // 목록 조회: githubRepository fetch join으로 N+1 방지, createdAt(즐겨찾기한 시각) 최신순
    @Query("SELECT f FROM UserFavorite f JOIN FETCH f.githubRepository " +
            "WHERE f.user.userId = :userId ORDER BY f.createdAt DESC")
    List<UserFavorite> findAllByUserIdWithRepository(@Param("userId") Long userId);
}
