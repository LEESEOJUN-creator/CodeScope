package com.codescope.api.controller.repo;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.repo.dto.GithubRepositoryRequest;
import com.codescope.domain.repo.dto.GithubRepositoryResponse;
import com.codescope.domain.repo.service.GithubRepositoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "GithubRepository", description = "GitHub 레포지토리 관리")
@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class GithubRepositoryController {

    private final GithubRepositoryService githubRepositoryService;

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
}
