package com.codescope.domain.repo.entity;

import com.codescope.common.entity.BaseEntity;
import com.codescope.common.persistence.PGvectorType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

@Entity
@Table(name = "repo_embeddings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepoEmbedding extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "repo_embedding_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false, unique = true)
    private GithubRepository repository;

    // 매핑 방식: PGvectorType(PGvector 기반 커스텀 Hibernate UserType) + float[]
    // 왜: 처음엔 Hibernate 6.6 내장 @JdbcTypeCode(SqlTypes.VECTOR)를 시도했으나,
    //     실제 INSERT 시 float[]를 VARBINARY(bytea)로 바인딩해
    //     "column is of type vector but expression is of type bytea" 에러로
    //     깨지는 것을 실측으로 확인했다(Hibernate 자체 한계). pgvector-java의
    //     PGvector(PGobject 상속)로 직접 감싸 PreparedStatement.setObject()에
    //     넘기면 드라이버가 인식하는 텍스트 포맷으로 정상 직렬화된다.
    //     PGvectorType(common/persistence) 참조.
    @Type(PGvectorType.class)
    @Column(name = "embedding", columnDefinition = "vector(768)")
    private float[] embedding;

    public static RepoEmbedding of(GithubRepository repository, float[] embedding) {
        RepoEmbedding repoEmbedding = new RepoEmbedding();
        repoEmbedding.repository = repository;
        repoEmbedding.embedding = embedding;
        return repoEmbedding;
    }

    public void updateEmbedding(float[] embedding) {
        this.embedding = embedding;
    }
}
