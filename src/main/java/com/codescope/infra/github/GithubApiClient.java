package com.codescope.infra.github;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubApiClient {

    private static final DateTimeFormatter QUERY_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final int RATE_LIMIT_WARN_THRESHOLD = 5;

    private final RestClient githubRestClient;

    @Value("${github.collection.queries}")
    private List<String> collectionQueries;
    @Value("${github.collection.per-query:10}")
    private int perQuery;

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Email(String email, boolean primary, boolean verified) {}

    public String fetchVerifiedEmail(String oauthToken) {
        Email[] emails = githubRestClient.get().uri("/user/emails")
                .headers(headers -> headers.setBearerAuth(oauthToken))
                .retrieve().body(Email[].class);
        if (emails == null) return null;
        return java.util.Arrays.stream(emails).filter(Email::verified)
                .filter(email -> email.email() != null && !email.email().isBlank())
                .sorted(java.util.Comparator.comparing(Email::primary).reversed())
                .map(Email::email).findFirst().orElse(null);
    }

    // GitHub Search API(/search/repositories) 호출
    // 왜 pushed 조건을 매번 동적 계산하는가: 하드코딩된 날짜는 시간이 지날수록
    //   "최근 활성"이라는 의미가 무색해짐 → 호출 시점 기준 최근 6개월로 매번 재계산
    // 왜 per_page=30인가: 토큰 있어도 Search API는 별도로 초당/분당 Rate Limit이
    //   더 빡빡해서(30req/min), 우선 적게 시작
    public List<TrendingRepoDto> searchTrending() {
        var unique = new java.util.LinkedHashMap<String, TrendingRepoDto>();
        for (String seed : collectionQueries) {
            try {
                searchRepositories(seed).forEach(repo -> unique.putIfAbsent(repo.fullName(), repo));
            } catch (RestClientException e) {
                log.warn("기술별 수집 실패: query={}, error={}", seed, e.getClass().getSimpleName());
                // 인증/한도 오류 후 나머지 요청도 연속 실패하는 것을 방지한다.
                if (e instanceof org.springframework.web.client.HttpClientErrorException http
                        && (http.getStatusCode().value() == 403 || http.getStatusCode().value() == 429
                        || http.getStatusCode().value() == 401)) break;
            }
        }
        return List.copyOf(unique.values());
    }

    private List<TrendingRepoDto> searchRepositories(String seed) {
        String pushedSince = LocalDate.now().minusMonths(6).format(QUERY_DATE_FORMAT);
        String query = seed + " archived:false fork:false pushed:>" + pushedSince;

        ResponseEntity<GithubSearchResponse> entity = githubRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/repositories")
                        .queryParam("q", query)
                        .queryParam("sort", "stars")
                        .queryParam("order", "desc")
                        .queryParam("per_page", Math.clamp(perQuery, 1, 100))
                        .build())
                .retrieve()
                .toEntity(GithubSearchResponse.class);

        logRateLimitRemaining(entity);

        GithubSearchResponse response = entity.getBody();

        if (response == null || response.items() == null) {
            log.warn("GitHub 트렌드 검색 응답이 비어있음: query={}", query);
            return Collections.emptyList();
        }

        log.info("GitHub 트렌드 검색 완료: query={}, totalCount={}, 조회건수={}",
                query, response.totalCount(), response.items().size());

        return response.items();
    }

    public GithubIssueSearchResponse searchStarterIssues(List<String> repositories, int limit) {
        if (repositories.isEmpty()) return new GithubIssueSearchResponse(List.of(), false);
        // 저장된 fullName만 사용하더라도 search qualifier 삽입은 허용하지 않는다.
        if (repositories.stream().anyMatch(name -> !name.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            throw new IllegalArgumentException("잘못된 GitHub 저장소 이름입니다.");
        }
        String query = "is:issue is:open no:assignee label:\"good first issue\" "
                + repositories.stream().map(name -> "repo:" + name).collect(java.util.stream.Collectors.joining(" "));
        var response = githubRestClient.get().uri(builder -> builder.path("/search/issues")
                        .queryParam("q", query).queryParam("sort", "updated").queryParam("order", "desc")
                        .queryParam("per_page", Math.clamp(limit, 1, 100)).build())
                .retrieve().body(GithubIssueSearchResponse.class);
        if (response == null || response.items() == null) {
            throw new IllegalStateException("GitHub 이슈 검색 응답이 비어 있습니다.");
        }
        return response;
    }

    // GitHub API: GET /repos/{fullName}/readme
    // 왜 Accept: application/vnd.github.raw+json인가: 기본 Accept(application/vnd.github+json)로
    //   호출하면 JSON envelope(content가 Base64 인코딩된 형태)로 응답이 와서
    //   디코딩이 별도로 필요함. raw+json을 지정하면 README 원문 텍스트를
    //   바로 String으로 받을 수 있음
    // 왜 별도 try-catch가 없는가: 404(README 없음) 등 실패는 호출부(EmbedConsumer)가
    //   Kafka 재시도/DLT 판단을 위해 직접 예외를 받아야 하므로, 여기서 삼키지 않고
    //   그대로 던져지도록 둠
    // 왜 owner/repo를 분리해서 넘기는가: fullName("owner/repo") 전체를 하나의
    //   경로 템플릿 변수로 넘기면 URI 인코딩 과정에서 "/"가 %2F로 인코딩되어
    //   실제 경로가 깨짐(GitHub가 404 반환) → 경로 세그먼트 2개로 분리해서 전달
    public String fetchReadme(String fullName) {
        String[] parts = fullName.split("/", 2);

        // 왜 명시적으로 검증하는가: 검증 없이 parts[1]에 접근하면 "/"가 없는 값이
        //   들어왔을 때 ArrayIndexOutOfBoundsException이 난다. 그 예외는 원인이
        //   드러나지 않아(어떤 값이 잘못됐는지 메시지에 없음) DLT 로그만 보고는
        //   추적이 어렵다. 어떤 입력이 왜 거부됐는지 메시지에 담아 던진다.
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "fullName은 'owner/repo' 형식이어야 합니다: " + fullName);
        }

        String owner = parts[0];
        String repo = parts[1];

        return githubRestClient.get()
                .uri("/repos/{owner}/{repo}/readme", owner, repo)
                .header("Accept", "application/vnd.github.raw+json")
                .retrieve()
                .body(String.class);
    }

    // 왜 X-RateLimit-Remaining을 로깅하는가: Search API는 인증해도 분당 30회로
    //   Rate Limit이 빡빡함 → 소진되기 전에 로그로 미리 감지하기 위함
    private void logRateLimitRemaining(ResponseEntity<GithubSearchResponse> entity) {
        String remainingHeader = entity.getHeaders().getFirst(RATE_LIMIT_REMAINING_HEADER);

        if (remainingHeader == null) {
            log.debug("{} 헤더가 응답에 없음", RATE_LIMIT_REMAINING_HEADER);
            return;
        }

        // 왜 파싱 실패를 삼키는가: 이 메서드는 관찰용 부가 로직이다.
        //   GitHub이 예기치 못한 형식의 헤더를 보냈다는 이유로 정상 수집 흐름이
        //   NumberFormatException으로 죽으면 본말이 전도된다.
        int remaining;
        try {
            remaining = Integer.parseInt(remainingHeader.trim());
        } catch (NumberFormatException e) {
            log.debug("{} 헤더를 숫자로 파싱할 수 없음: value={}",
                    RATE_LIMIT_REMAINING_HEADER, remainingHeader);
            return;
        }

        if (remaining < RATE_LIMIT_WARN_THRESHOLD) {
            log.warn("GitHub API Rate Limit 임박: remaining={}", remaining);
        } else {
            log.debug("GitHub API Rate Limit 여유 있음: remaining={}", remaining);
        }
    }
}
