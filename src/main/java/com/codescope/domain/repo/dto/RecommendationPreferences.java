package com.codescope.domain.repo.dto;

public record RecommendationPreferences(String goal, String experience) {
    public RecommendationPreferences {
        if (!java.util.Set.of("learn", "contribute", "adopt").contains(goal)
                || !java.util.Set.of("beginner", "intermediate", "advanced").contains(experience)) {
            throw new IllegalArgumentException("올바른 추천 목적과 경험 수준을 선택해주세요.");
        }
    }
    public static RecommendationPreferences defaults() {
        return new RecommendationPreferences("learn", "intermediate");
    }
    public String goalDescription() {
        return switch (goal) {
            case "contribute" -> "오픈소스 기여: 문서·테스트 등 검증 가능한 첫 기여 준비를 우선";
            case "adopt" -> "프로젝트 도입: 기능 적합성·설치 전 확인 사항·대안과의 차이를 우선";
            default -> "학습: 개념을 익히는 순서·작은 실습·학습 결과물을 우선";
        };
    }
    public String experienceDescription() {
        return switch (experience) {
            case "beginner" -> "입문: 배경지식과 용어를 풀어서 설명하고 작은 단계로 시작";
            case "advanced" -> "숙련: 설계 선택·제약·확장 지점 중심으로 설명";
            default -> "실무 경험: 익숙한 기본 개념은 줄이고 적용 방법과 트레이드오프 중심";
        };
    }
}
