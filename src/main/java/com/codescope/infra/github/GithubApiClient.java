package com.codescope.infra.github;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    // GitHub Search API(/search/repositories) 호출
    // 왜 pushed 조건을 매번 동적 계산하는가: 하드코딩된 날짜는 시간이 지날수록
    //   "최근 활성"이라는 의미가 무색해짐 → 호출 시점 기준 최근 6개월로 매번 재계산
    // 왜 per_page=30인가: 토큰 있어도 Search API는 별도로 초당/분당 Rate Limit이
    //   더 빡빡해서(30req/min), 우선 적게 시작
    public List<TrendingRepoDto> searchTrending() {
        String pushedSince = LocalDate.now().minusMonths(6).format(QUERY_DATE_FORMAT);
        String query = "stars:>1000 pushed:>" + pushedSince;

        ResponseEntity<GithubSearchResponse> entity = githubRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/repositories")
                        .queryParam("q", query)
                        .queryParam("sort", "stars")
                        .queryParam("order", "desc")
                        .queryParam("per_page", 30)
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

        int remaining = Integer.parseInt(remainingHeader);

        if (remaining < RATE_LIMIT_WARN_THRESHOLD) {
            log.warn("GitHub API Rate Limit 임박: remaining={}", remaining);
        } else {
            log.debug("GitHub API Rate Limit 여유 있음: remaining={}", remaining);
        }
    }
}
