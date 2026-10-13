package com.codescope.domain.repo.service;

import com.codescope.client.llm.EmbeddingService;
import com.codescope.domain.repo.dto.RepositoryEvidence;
import com.codescope.domain.repo.repository.DiscoveryRepository;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class RecommendationSearchService {
    private final EmbeddingService embeddings;
    private final DiscoveryRepository repositories;
    private final StackKeywords keywords;
    @Value("${codescope.recommendation.candidate-limit:10}")
    private int candidateLimit;

    public List<RepositoryEvidence> search(String stack) {
        var terms = keywords.parse(stack);
        var vector = embeddings.embedQuery(String.join(", ", terms));
        long started = System.nanoTime();
        var candidates = repositories.search(new PGvector(vector).toString(), terms, candidateLimit);
        log.info("추천 검색 소요시간: {}ms (candidateLimit={}, returned={})",
                (System.nanoTime() - started) / 1_000_000, candidateLimit, candidates.size());
        return candidates;
    }
}
