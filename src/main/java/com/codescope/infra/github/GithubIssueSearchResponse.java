package com.codescope.infra.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubIssueSearchResponse(List<Item> items,
        @JsonProperty("incomplete_results") boolean incompleteResults) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(long id, int number, String title, String body,
                       @JsonProperty("html_url") String htmlUrl,
                       @JsonProperty("updated_at") String updatedAt) {}
}
