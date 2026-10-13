package com.codescope.domain.repo.service;

import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.repository.TopicJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RepositoryTopicService {
    private final GithubRepositoryJpaRepository repositories;
    private final TopicJpaRepository topics;
    private final JdbcTemplate jdbc;

    @Transactional
    public void sync(Long repoId, List<String> names) {
        if (names == null) return; // 구버전 Kafka 메시지는 topics 필드가 없다.
        var repo = repositories.findById(repoId).orElseThrow();
        var resolved = names.stream().filter(n -> n != null && !n.isBlank())
                .map(n -> n.strip().toLowerCase(Locale.ROOT)).filter(n -> n.length() <= 50)
                .distinct().sorted().map(name -> {
                    // 여러 수집 워커가 같은 토픽을 동시에 생성해도 트랜잭션을 깨지 않는다.
                    jdbc.update("INSERT INTO topics(name) VALUES (?) ON CONFLICT (name) DO NOTHING", name);
                    return topics.findByName(name).orElseThrow();
                }).toList();
        repo.updateTopics(resolved);
    }
}
