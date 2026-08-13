package com.codescope.domain.repo.repository;

import com.codescope.support.PostgresTestContainer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codescope.domain.repo.dto.SearchCondition;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.infra.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GithubRepositoryQueryRepository.search()의 페이지네이션 검증 테스트
 * - content/totalElements/totalPages가 Pageable에 맞게 계산되는지
 * - 실제 SQL 로그(org.hibernate.SQL)에 limit/offset이 찍히고
 *   topics(repo_topic) join이 메인 쿼리에 섞여 들어가지 않는지 확인한다
 *
 * 왜 GithubRepositoryQueryRepositoryTest와 별도 클래스로 분리했는가:
 * - 기존 클래스는 BooleanBuilder 조건 조합(키워드/language/topic/minStars)이
 *   결과 "개수/내용"에 맞게 걸리는지를 검증하는 데 집중돼 있고,
 *   여기서는 같은 조건이라도 페이지 경계값(15/17건)과 SQL 로그 캡처라는
 *   별개의 관심사를 검증한다. bulk fixture(15~17건)를 기존 3건짜리
 *   setUpFixtures()와 섞으면 기존 테스트의 가독성이 떨어질 위험이 있음
 * - @DataJpaTest + @Import 구성이 동일해 Spring TestContext가 캐싱되므로
 *   클래스를 분리해도 컨텍스트 재기동 비용은 없음
 */
@DataJpaTest
@Import({QuerydslConfig.class, GithubRepositoryQueryRepository.class})
class GithubRepositoryQueryRepositoryPaginationTest extends PostgresTestContainer {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(GithubRepositoryQueryRepositoryPaginationTest.class);

    @Autowired
    private GithubRepositoryJpaRepository githubRepositoryJpaRepository;

    @Autowired
    private GithubRepositoryQueryRepository githubRepositoryQueryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private void createRepositories(int count, String language) {
        for (int i = 1; i <= count; i++) {
            GithubRepository repo = GithubRepository.builder()
                    .name("paging-repo-" + i)
                    .fullName("paging-test/repo-" + i)
                    .description("pagination test repo " + i)
                    .language(language)
                    .starCount(i)
                    .forkCount(0)
                    .openIssueCount(0)
                    .githubUrl("https://github.com/paging-test/repo-" + i)
                    .build();
            githubRepositoryJpaRepository.save(repo);
        }
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("search_페이징_정상_동작: 15건을 size=5로 조회하면 5건씩 3페이지로 나뉜다")
    void search_페이징_정상_동작() {
        // given
        createRepositories(15, "Java");
        SearchCondition condition = new SearchCondition(null, "Java", null, null);
        Pageable pageable = PageRequest.of(0, 5);

        // when
        Page<GithubRepository> result = githubRepositoryQueryRepository.search(condition, pageable);

        // then
        assertThat(result.getContent()).hasSize(5);
        assertThat(result.getTotalElements()).isEqualTo(15);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("search_마지막_페이지_나머지_건수: 17건을 size=5로 조회하면 마지막 페이지(3, 0-base)는 2건만 남는다")
    void search_마지막_페이지_나머지_건수() {
        // given
        createRepositories(17, "Java");
        SearchCondition condition = new SearchCondition(null, "Java", null, null);
        Pageable pageable = PageRequest.of(3, 5);

        // when
        Page<GithubRepository> result = githubRepositoryQueryRepository.search(condition, pageable);

        // then
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(17);
        assertThat(result.getTotalPages()).isEqualTo(4);
    }

    @Test
    @DisplayName("search_결과_없음_빈_페이지_반환: 조건에 맞는 데이터가 없으면 에러 없이 빈 페이지를 반환한다")
    void search_결과_없음_빈_페이지_반환() {
        // given
        createRepositories(5, "Java");
        SearchCondition condition = new SearchCondition(null, "Go", null, null);
        Pageable pageable = PageRequest.of(0, 5);

        // when
        Page<GithubRepository> result = githubRepositoryQueryRepository.search(condition, pageable);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("search_SQL_로그에_limit_offset_확인: 메인 쿼리에 limit/offset이 찍히고 topics join은 섞이지 않는다")
    void search_SQL_로그에_limit_offset_확인() {
        // given
        createRepositories(15, "Java");
        SearchCondition condition = new SearchCondition(null, "Java", null, null);
        Pageable pageable = PageRequest.of(1, 5); // offset=5, limit=5

        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);

        // when
        try {
            githubRepositoryQueryRepository.search(condition, pageable);
        } finally {
            sqlLogger.detachAppender(appender);
            appender.stop();
        }

        List<String> capturedSqls = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        log.info("===== search() 실행 중 캡처된 SQL 목록 =====\n{}",
                String.join("\n---\n", capturedSqls));

        // then
        // 메인 컨텐츠 쿼리(offset/limit) 1개 + count 쿼리 1개, topics는 순회하지 않았으므로 배치 조회 쿼리는 없어야 함
        assertThat(capturedSqls).hasSize(2);

        // format_sql: true로 SQL이 여러 줄로 포맷팅되므로 공백을 정규화한 뒤 비교한다
        // 이 Hibernate 6.6 + PostgreSQL 15 조합은 "limit ? offset ?"이 아니라
        // ANSI 표준 "offset ? rows fetch first ? rows only" 구문을 사용한다
        // (실제 로그로 확인한 문법, limit 키워드는 나오지 않음)
        String contentSql = normalizeWhitespace(capturedSqls.get(0));
        assertThat(contentSql).contains("from github_repository");
        assertThat(contentSql).contains("offset");
        assertThat(contentSql).contains("fetch first");
        assertThat(contentSql).contains("rows only");
        assertThat(contentSql).doesNotContain("repo_topic");

        String countSql = normalizeWhitespace(capturedSqls.get(1));
        assertThat(countSql).contains("count(");
        assertThat(countSql).doesNotContain("repo_topic");
    }

    private String normalizeWhitespace(String sql) {
        return sql.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
