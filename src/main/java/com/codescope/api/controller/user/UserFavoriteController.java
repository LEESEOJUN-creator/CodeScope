package com.codescope.api.controller.user;

import com.codescope.common.response.ApiResponse;
import com.codescope.domain.user.dto.FavoriteResponse;
import com.codescope.domain.user.service.UserFavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "UserFavorite", description = "레포지토리 즐겨찾기")
@RestController
@RequestMapping("/api/users/me/bookmarks")
@RequiredArgsConstructor
public class UserFavoriteController {

    private final UserFavoriteService userFavoriteService;

    @Operation(summary = "레포지토리 즐겨찾기 추가")
    @PostMapping("/{repoId}")
    public ResponseEntity<ApiResponse<Void>> addFavorite(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long repoId
    ) {
        userFavoriteService.addFavorite(userId, repoId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(null));
    }

    @Operation(summary = "즐겨찾기 목록 조회 (최근 즐겨찾기한 순)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<FavoriteResponse>>> getFavorites(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.success(userFavoriteService.getFavorites(userId)));
    }

    @Operation(summary = "레포지토리 즐겨찾기 해제")
    @DeleteMapping("/{repoId}")
    public ResponseEntity<Void> removeFavorite(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long repoId
    ) {
        userFavoriteService.removeFavorite(userId, repoId);
        return ResponseEntity.noContent().build();
    }
}
