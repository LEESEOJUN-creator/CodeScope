package com.codescope.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * JPA 테스트가 공유하는 PostgreSQL 테스트 컨테이너.
 *
 * 왜 도입했는가:
 *   기존에는 @AutoConfigureTestDatabase(replace = NONE)으로 로컬/K8s에 떠 있는
 *   실제 PostgreSQL에 그대로 붙었다. 수집 파이프라인이 실제로 돌기 시작하면서
 *   그 DB에 진짜 GitHub 레포 데이터가 쌓였고, "내 픽스처만 있다"고 가정하고
 *   개수를 단언하던 테스트들이 한꺼번에 깨졌다
 *   (expected: 3L but was: 34L — 픽스처 3건 + 실제 수집 31건).
 *   테스트는 자기 데이터만 있는 DB 위에서 돌아야 한다.
 *
 * 왜 @Container(클래스별 컨테이너)가 아니라 static 싱글턴인가:
 *   @Container를 쓰면 테스트 클래스마다 컨테이너가 새로 뜨고 내려간다.
 *   그러면 클래스마다 JDBC URL(포트)이 달라져 @DynamicPropertySource 값이
 *   바뀌고, Spring TestContext 캐시가 무효화되어 애플리케이션 컨텍스트가
 *   매번 새로 뜬다. 이 프로젝트는 @DataJpaTest 클래스가 6개라 그 비용이 크다.
 *   컨테이너 하나를 JVM 전체에서 공유하면 URL이 고정되어 컨텍스트 캐싱이
 *   유지된다. 컨테이너 정리는 Testcontainers의 Ryuk가 JVM 종료 시 처리한다.
 *
 * 테스트 간 데이터 격리는 @DataJpaTest가 기본 제공하는 트랜잭션 롤백이
 * 담당하므로, 컨테이너를 공유해도 서로의 데이터를 보지 않는다.
 */
public abstract class PostgresTestContainer {

    // 왜 postgres:15가 아니라 pgvector/pgvector:pg15인가:
    //   V3 마이그레이션이 CREATE EXTENSION vector를 실행하는데, 순정
    //   postgres:15 이미지에는 pgvector 확장 파일 자체가 없어 Flyway
    //   마이그레이션이 이 컨테이너를 쓰는 모든 @DataJpaTest에서 실패한다.
    //   pgvector/pgvector:pg15는 postgres:15 기반 위에 확장만 추가한
    //   이미지라 기존 테스트 동작에는 영향이 없다.
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    org.testcontainers.utility.DockerImageName
                            .parse("pgvector/pgvector:pg15")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("codescope_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
}
