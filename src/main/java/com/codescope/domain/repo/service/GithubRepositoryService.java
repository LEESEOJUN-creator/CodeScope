package com.codescope.domain.repo.service;

import com.codescope.domain.repo.dto.GithubRepositoryRequest;
import com.codescope.domain.repo.dto.GithubRepositoryResponse;
import com.codescope.domain.repo.dto.SearchCondition;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.Topic;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import com.codescope.domain.repo.repository.GithubRepositoryQueryRepository;
import com.codescope.domain.repo.repository.TopicJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GithubRepositoryService {

    private final GithubRepositoryJpaRepository repositoryJpaRepository;
    private final TopicJpaRepository topicJpaRepository;
    private final GithubRepositoryQueryRepository repositoryQueryRepository;

    public List<GithubRepositoryResponse> getAll() {
        return repositoryJpaRepository.findAllWithTopics()
                .stream()
                .map(GithubRepositoryResponse::from)
                .toList();
    }

    public GithubRepositoryResponse getById(Long id) {
        GithubRepository entity = repositoryJpaRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("레포지토리를 찾을 수 없습니다. id=" + id));
        return GithubRepositoryResponse.from(entity);
    }

    public List<GithubRepositoryResponse> getByLanguage(String language) {
        return repositoryJpaRepository.findByLanguageOrderByStarCountDesc(language)
                .stream()
                .map(GithubRepositoryResponse::from)
                .toList();
    }

    @Transactional
    public GithubRepositoryResponse save(GithubRepositoryRequest request) {
        if (repositoryJpaRepository.existsByFullName(request.getFullName())) {
            throw new IllegalArgumentException("이미 존재하는 레포지토리입니다: " + request.getFullName());
        }

        GithubRepository entity = GithubRepository.builder()
                .name(request.getName())
                .fullName(request.getFullName())
                .description(request.getDescription())
                .language(request.getLanguage())
                .starCount(request.getStarCount())
                .forkCount(request.getForkCount())
                .openIssueCount(request.getOpenIssueCount())
                .githubUrl(request.getGithubUrl())
                .build();

        repositoryJpaRepository.save(entity);

        if (request.getTopics() != null && !request.getTopics().isEmpty()) {
            List<Topic> resolvedTopics = request.getTopics().stream()
                    .map(name -> topicJpaRepository.findByName(name)
                            .orElseGet(() -> topicJpaRepository.save(Topic.of(name))))
                    .toList();
            entity.updateTopics(resolvedTopics);
        }

        return GithubRepositoryResponse.from(entity);
    }

    public Page<GithubRepositoryResponse> searchRepositories(SearchCondition condition, Pageable pageable) {
        return repositoryQueryRepository.search(condition, pageable)
                .map(GithubRepositoryResponse::from);
    }

    @Transactional
    public void delete(Long id) {
        GithubRepository entity = repositoryJpaRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("레포지토리를 찾을 수 없습니다. id=" + id));
        repositoryJpaRepository.delete(entity);
    }
}
