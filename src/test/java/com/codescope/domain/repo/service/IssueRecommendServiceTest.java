package com.codescope.domain.repo.service;
import com.codescope.client.llm.LlmClient;
import com.codescope.domain.repo.dto.RepositoryEvidence;
import com.codescope.infra.github.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IssueRecommendServiceTest {
    final RecommendationSearchService search = mock(RecommendationSearchService.class);
    final GithubApiClient github = mock(GithubApiClient.class);
    final LlmClient llm = mock(LlmClient.class);
    final IssueRecommendService service = new IssueRecommendService(search, github, llm, new ObjectMapper());
    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "repoLimit", 5);
        ReflectionTestUtils.setField(service, "issueLimit", 10);
    }
    @Test void noMatchingCorpusDoesNotInventOrFetchIssues() {
        when(search.search("jev")).thenReturn(List.of());
        assertThat(service.recommend("jev").issues()).isEmpty();
        verifyNoInteractions(github, llm);
    }
    @Test void retainsRealIssuesWhenAiIsUnavailableAndFiltersForeignUrls() {
        when(search.search("java")).thenReturn(List.of(new RepositoryEvidence(
                1L, "owner/repo", "", "Java", 1, "", List.of(), List.of("java"))));
        when(github.searchStarterIssues(List.of("owner/repo"), 10)).thenReturn(new GithubIssueSearchResponse(List.of(
                new GithubIssueSearchResponse.Item(1, 3, "Add docs", "Body", "https://github.com/owner/repo/issues/3", ""),
                new GithubIssueSearchResponse.Item(2, 4, "Foreign", "", "https://example.com/phishing", "")), true));
        when(llm.generate(anyString())).thenThrow(new IllegalStateException("unavailable"));
        var response = service.recommend("java");
        assertThat(response.issues()).hasSize(1);
        assertThat(response.issues().getFirst().url()).isEqualTo("https://github.com/owner/repo/issues/3");
        assertThat(response.recommendation()).contains("일부만", "AI 설명을 생성하지 못했습니다");
    }
}
