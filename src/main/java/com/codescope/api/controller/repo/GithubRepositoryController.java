package com.codescope.api.controller.repo;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.repo.dto.GithubRepositoryRequest;
import com.codescope.domain.repo.dto.GithubRepositoryResponse;
import com.codescope.domain.repo.dto.SearchCondition;
import com.codescope.domain.repo.service.GithubRepositoryService;
import com.codescope.domain.repo.service.TrendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "GithubRepository", description = "GitHub 레포지토리 관리")
@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class GithubRepositoryController {

    private final GithubRepositoryService githubRepositoryService;
    private final TrendService trendService;

    @Operation(summary = "전체 레포지토리 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<GithubRepositoryResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(githubRepositoryService.getAll()));
    }

    @Operation(summary = "단건 레포지토리 조회")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GithubRepositoryResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(githubRepositoryService.getById(id)));
    }

    @Operation(summary = "언어별 레포지토리 조회")
    @GetMapping("/language/{language}")
    public ResponseEntity<ApiResponse<List<GithubRepositoryResponse>>> getByLanguage(@PathVariable String language) {
        return ResponseEntity.ok(ApiResponse.success(githubRepositoryService.getByLanguage(language)));
    }

    @Operation(summary = "동적 조건 레포지토리 검색")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Page<GithubRepositoryResponse>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) Integer minStars,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        SearchCondition condition = new SearchCondition(keyword, language, topic, minStars);
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.success(githubRepositoryService.searchRepositories(condition, pageable)));
    }

    @Operation(summary = "레포지토리 저장")
    @PostMapping
    public ResponseEntity<ApiResponse<GithubRepositoryResponse>> save(@Valid @RequestBody GithubRepositoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(githubRepositoryService.save(request)));
    }

    @Operation(summary = "레포지토리 삭제")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        githubRepositoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "트렌드 랭킹 조회 (Redis Sorted Set 기반)")
    @GetMapping("/trending")
    public ResponseEntity<ApiResponse<List<GithubRepositoryResponse>>> getTrending(
            @RequestParam(defaultValue = "10") int limit
    ) {
        List<Long> topRepoIds = trendService.getTopRepos(limit);
        return ResponseEntity.ok(ApiResponse.success(fetchByBatch(topRepoIds)));
    }

    // 캐시(popularRepos) 적용된 getById를 id 개수만큼 반복 호출
    // 캐시 히트가 많으면 쿼리 수가 적지만, 미스가 많으면 N번 SELECT
    private List<GithubRepositoryResponse> fetchByLoop(List<Long> ids) {
        return ids.stream()
                .map(githubRepositoryService::getById)
                .toList();
    }

    // findByIdIn으로 한 번에 조회 (SELECT 1번, 캐시 미적용)
    // DB가 반환하는 순서를 보장하지 않아 트렌드 랭킹 순서(ids 순서)로 재정렬
    private List<GithubRepositoryResponse> fetchByBatch(List<Long> ids) {
        Map<Long, GithubRepositoryResponse> byId = githubRepositoryService.getByIds(ids).stream()
                .collect(Collectors.toMap(GithubRepositoryResponse::getId, Function.identity()));

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
