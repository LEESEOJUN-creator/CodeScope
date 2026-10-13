package com.codescope.kafka.dto;

public record CollectMessage(
        String name,
        String fullName,
        String description,
        String language,
        int starCount,
        int forkCount,
        int openIssueCount,
        String githubUrl,
        java.util.List<String> topics
) {
    // 이전 버전 메시지와 테스트 픽스처 호환. null topics는 기존 연결을 보존한다.
    public CollectMessage(String name, String fullName, String description, String language,
                          int starCount, int forkCount, int openIssueCount, String githubUrl) {
        this(name, fullName, description, language, starCount, forkCount, openIssueCount, githubUrl, null);
    }
}
