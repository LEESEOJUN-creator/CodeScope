package com.codescope.infra.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// GitHub Search API(/search/repositories) 응답 전체 envelope
// (total_count/incomplete_results는 현재 사용하지 않지만 역직렬화를 위해 필드는 유지)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubSearchResponse(
        @JsonProperty("total_count")
        long totalCount,

        @JsonProperty("incomplete_results")
        boolean incompleteResults,

        List<TrendingRepoDto> items
) {
}
