package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.TrendScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TrendScoreJpaRepository extends JpaRepository<TrendScore, Long> {
    Optional<TrendScore> findByRepositoryId(Long repoId);

    // LLM 분석 텍스트 저장(TrendAnalysisService). 없으면 score=0으로 생성, 있으면
    // analysis_text만 갱신한다.
    //
    // 왜 "조회 후 save" 대신 upsert 한 문장인가: 같은 레포에 대한 분석이 동시에
    //   끝나면 두 요청 모두 "행 없음"을 보고 INSERT해 uk_trend_scores_repo_id
    //   위반이 난다(조회와 INSERT 사이의 TOCTOU). ON CONFLICT는 DB가 원자적으로
    //   처리해 경합 자체가 생기지 않고, 트랜잭션도 이 문장 하나로 짧게 끝난다.
    // 왜 now()가 아니라 :now 파라미터인가: created_at 등은 JPA Auditing이 JVM
    //   시각(LocalDateTime.now())으로 채운다. DB now()는 DB 세션 타임존 기준이라
    //   컨테이너(UTC)와 앱(KST) 사이에 9시간 어긋난 값이 섞인다.
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO trend_scores (repo_id, score, calculated_at, analysis_text, created_at, updated_at)
            VALUES (:repoId, 0, :now, :analysisText, :now, :now)
            ON CONFLICT (repo_id) DO UPDATE
                SET analysis_text = EXCLUDED.analysis_text,
                    updated_at    = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsertAnalysisText(@Param("repoId") Long repoId,
                           @Param("analysisText") String analysisText,
                           @Param("now") LocalDateTime now);
}
