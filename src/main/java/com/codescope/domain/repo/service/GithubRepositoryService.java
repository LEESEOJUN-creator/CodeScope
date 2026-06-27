package com.codescope.domain.repo.service;

import com.codescope.domain.repo.dto.GithubRepositoryRequest;
import com.codescope.domain.repo.dto.GithubRepositoryResponse;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.repository.GithubRepositoryJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GithubRepositoryService {

    private final GithubRepositoryJpaRepository repositoryJpaRepository;

    public List<GithubRepositoryResponse> getAll() {
        return repositoryJpaRepository.findAll()
                .stream()
                .map(GithubRepositoryResponse::from)
                .collect(Collectors.toList());
    }

    public GithubRepositoryResponse getById(Long id) {
        GithubRepository entity = repositoryJpaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("레포지토리를 찾을 수 없습니다. id=" + id));
        return GithubRepositoryResponse.from(entity);
    }

    public List<GithubRepositoryResponse> getByLanguage(String language) {
        return repositoryJpaRepository.findByLanguageOrderByStarCountDesc(language)
                .stream()
                .map(GithubRepositoryResponse::from)
                .collect(Collectors.toList());
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
                .topics(request.getTopics())
                .build();

        return GithubRepositoryResponse.from(repositoryJpaRepository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        GithubRepository entity = repositoryJpaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("레포지토리를 찾을 수 없습니다. id=" + id));
        repositoryJpaRepository.delete(entity);
    }
}
