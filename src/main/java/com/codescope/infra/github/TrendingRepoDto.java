package com.codescope.infra.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// GitHub Search API(/search/repositories) 응답의 items 배열 원소 중
// 필요한 필드만 매핑. 그 외 필드는 무시
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrendingRepoDto(
        String name,

        @JsonProperty("full_name")
        String fullName,

        String description,

        String language,

        @JsonProperty("stargazers_count")
        int starsCount,

        @JsonProperty("forks_count")
        int forksCount,

        @JsonProperty("open_issues_count")
        int openIssuesCount,

        @JsonProperty("html_url")
        String htmlUrl
) {
}
