package com.codescope.domain.repo.entity;

import com.codescope.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    // TODO [4주차]: pgvector 의존성 추가 후 vector(1536) 타입으로 변경
    @Column(columnDefinition = "text")
    private String embeddingJson;

    public static RepoEmbedding of(GithubRepository repository, String embeddingJson) {
        RepoEmbedding embedding = new RepoEmbedding();
        embedding.repository = repository;
        embedding.embeddingJson = embeddingJson;
        return embedding;
    }

    public void updateEmbedding(String embeddingJson) {
        this.embeddingJson = embeddingJson;
    }
}
