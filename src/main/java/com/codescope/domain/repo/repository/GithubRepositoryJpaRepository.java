package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.GithubRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GithubRepositoryJpaRepository extends JpaRepository<GithubRepository, Long> {

    // fullName으로 조회 (중복 저장 방지용)
    Optional<GithubRepository> findByFullName(String fullName);

    // 언어별 조회
    List<GithubRepository> findByLanguageOrderByStarCountDesc(String language);

    // 존재 여부 확인
    boolean existsByFullName(String fullName);

    // topics fetch join (N+1 방지, 페이징 없음)
    // 왜 LEFT JOIN FETCH인가: 처음엔 JOIN FETCH(inner join)였는데, 토픽이 하나도 없는
    //   레포는 inner join에서 탈락한다. 수집 파이프라인이 토픽을 저장하지 않아
    //   실데이터 30건이 전부 빠지고 GET /api/repos가 0건을 돌려줬다(2026-10-13 발견).
    // 왜 DISTINCT인가: 컬렉션 fetch join은 토픽 수만큼 레포 행이 복제된다. Hibernate 6는
    //   엔티티 중복을 자동 제거하지만, 의도를 쿼리에 명시해 둔다.
    @Query("SELECT DISTINCT r FROM GithubRepository r LEFT JOIN FETCH r.topics")
    List<GithubRepository> findAllWithTopics();

    // id 목록으로 일괄 조회 (트렌드 랭킹 등 배치 조회용, 반환 순서는 DB 순서라 보장 안 됨)
    List<GithubRepository> findByIdIn(List<Long> ids);
}