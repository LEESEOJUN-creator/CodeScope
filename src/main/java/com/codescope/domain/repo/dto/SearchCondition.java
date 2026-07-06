package com.codescope.domain.repo.dto;

public record SearchCondition(
        String keyword,
        String language,
        String topic,
        Integer minStars
) {
}
