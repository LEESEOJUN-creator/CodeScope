package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.RepoEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RepoEmbeddingJpaRepository extends JpaRepository<RepoEmbedding, Long> {
    Optional<RepoEmbedding> findByRepositoryId(Long repoId);

    // 코사인 거리(<=>) 기준 상위 N개 조회.
    // 왜 쿼리 벡터를 float[]가 아니라 String("[0.1,0.2,...]")로 받는가:
    //   엔티티 컬럼 매핑은 PGvectorType(커스텀 UserType)이 처리하지만,
    //   그건 엔티티 필드 바인딩 경로에만 적용되고 @Query 네이티브
    //   파라미터는 이 타입 시스템을 타지 않아 float[]를 그대로 바인딩할
    //   표준 경로가 없으므로, pgvector가 인식하는 텍스트 리터럴로 넘기고
    //   SQL에서 ::vector로 캐스팅한다.
    @Query(value = """
            SELECT * FROM repo_embeddings
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<RepoEmbedding> findNearestByEmbedding(@Param("queryVector") String queryVector, @Param("limit") int limit);

    // 왜 findNearestByEmbedding과 별도 메서드로 나눴는가:
    //   위 메서드는 pgvector 코사인 유사도 자체가 정상 동작하는지 검증하는
    //   테스트(RepoEmbeddingSimilarityTest)가 그대로 쓰고 있어 변경하면 그
    //   테스트의 전제가 깨진다. 반면 CLAUDE.md 원칙상 RAG 추천 쿼리는
    //   "반드시 status=EMBEDDED만" 필터링해야 하는데(과거엔 EMBEDDED였다가
    //   재시도 실패로 FAILED가 된 레포도 repo_embeddings엔 옛 벡터가 남아있어
    //   필터링 없이 쓰면 추천에 섞여 들어갈 수 있음을 실측으로 확인) — pgvector
    //   검증용과 실제 추천 비즈니스 로직용의 관심사가 다르므로 분리했다.
    @Query(value = """
            SELECT re.* FROM repo_embeddings re
            JOIN github_repository gr ON gr.github_repository_id = re.repo_id
            WHERE gr.process_status = 'EMBEDDED'
            ORDER BY re.embedding <=> CAST(:queryVector AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<RepoEmbedding> findNearestEmbeddedByEmbedding(@Param("queryVector") String queryVector, @Param("limit") int limit);
}
