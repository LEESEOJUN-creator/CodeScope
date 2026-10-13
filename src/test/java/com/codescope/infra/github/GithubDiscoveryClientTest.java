package com.codescope.infra.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GithubDiscoveryClientTest {
    GithubApiClient client;
    MockRestServiceServer server;
    @BeforeEach void setUp() {
        var builder = RestClient.builder().baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer collector-token");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubApiClient(builder.build());
        ReflectionTestUtils.setField(client, "collectionQueries", List.of("language:Java stars:>100", "topic:spring-boot stars:>100"));
        ReflectionTestUtils.setField(client, "perQuery", 10);
    }
    @Test void collectsAndDeduplicatesTopicsFromMultipleQueries() {
        String body = """
                {"items":[{"name":"spring-boot","full_name":"spring-projects/spring-boot",
                "language":"Java","topics":["spring-boot","java"]}],"total_count":1}
                """;
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/search/repositories"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/search/repositories"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var result = client.searchTrending();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().topics()).containsExactly("spring-boot", "java");
        server.verify();
    }
    @Test void issuesUseActualOpenUnassignedIssueSearchAndPreservePartialFlag() {
        server.expect(request -> {
            String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
            assertThat(query).contains("is:issue", "is:open", "no:assignee", "label:\"good first issue\"",
                    "repo:spring-projects/spring-boot");
        }).andRespond(withSuccess("""
                {"incomplete_results":true,"items":[{"id":12,"number":3,"title":"Document setup",
                "html_url":"https://github.com/spring-projects/spring-boot/issues/3"}]}
                """, MediaType.APPLICATION_JSON));
        var result = client.searchStarterIssues(List.of("spring-projects/spring-boot"), 10);
        assertThat(result.incompleteResults()).isTrue();
        assertThat(result.items().getFirst().number()).isEqualTo(3);
        server.verify();
    }
    @Test void privateEmailUsesUserTokenAndVerifiedPrimaryEmail() {
        server.expect(requestTo("https://api.github.com/user/emails"))
                .andExpect(header("Authorization", "Bearer user-oauth-token"))
                .andRespond(withSuccess("""
                        [{"email":"unverified@example.com","primary":true,"verified":false},
                         {"email":"secondary@example.com","primary":false,"verified":true},
                         {"email":"primary@example.com","primary":true,"verified":true}]
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.fetchVerifiedEmail("user-oauth-token")).isEqualTo("primary@example.com");
        server.verify();
    }
    @Test void doesNotTurnRateLimitFailureIntoEmptyIssueResults() {
        server.expect(anything()).andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.searchStarterIssues(List.of("owner/repo"), 10))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
    }
}
