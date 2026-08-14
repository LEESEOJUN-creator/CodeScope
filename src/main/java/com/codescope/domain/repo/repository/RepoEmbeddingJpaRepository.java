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
    //   pgvector-java 0.1.6은 Hibernate UserType이 아니라 순수 JDBC
    //   PGobject 래퍼만 제공한다(엔티티 컬럼 매핑은 Hibernate 6.6의
    //   내장 SqlTypes.VECTOR가 처리). 네이티브 쿼리 파라미터는 이
    //   내장 타입 시스템을 타지 않아 float[]를 그대로 바인딩할 표준
    //   경로가 없으므로, pgvector가 인식하는 텍스트 리터럴로 넘기고
    //   SQL에서 ::vector로 캐스팅한다.
    @Query(value = """
            SELECT * FROM repo_embeddings
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<RepoEmbedding> findNearestByEmbedding(@Param("queryVector") String queryVector, @Param("limit") int limit);
}
