package com.codescope.domain.repo.dto;

import com.codescope.domain.repo.entity.GithubRepository;

// RAG 프롬프트에 주입되는 후보 레포 + 최종 응답에 그대로 노출되는 후보
// 목록을 겸한다. LLM 응답 텍스트에 실제로 등장한 레포 이름이 이 목록
// 안에 있는지 대조하면 환각 여부를 검증할 수 있다(호출부 책임).
public record RecommendedRepoDto(
        Long id,
        String fullName,
        String description,
        String language,
        int starCount
) {
    public static RecommendedRepoDto from(GithubRepository entity) {
        return new RecommendedRepoDto(
                entity.getId(),
                entity.getFullName(),
                entity.getDescription(),
                entity.getLanguage(),
                entity.getStarCount()
        );
    }
}
