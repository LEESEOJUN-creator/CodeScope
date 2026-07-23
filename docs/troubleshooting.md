# Troubleshooting

## N+1 문제 재현 및 해결 (Fetch Join)

**문제**: GithubRepository-Topic이 @ManyToMany LAZY 관계로 설계되어 있어,
목록 조회 후 각 레포의 Topic을 순회할 때 레포 개수만큼 추가 쿼리가 발생.

**재현 방법**: given(레포/Topic 데이터 준비) → entityManager.flush()+clear()로
1차 캐시 무효화(같은 트랜잭션 내 given/when이면 캐시로 인해 N+1이
재현되지 않으므로 필수) → findAll() 후 각 repo.getTopics().size() 순회

**측정 결과 (쿼리 횟수)**:

| 데이터 규모 | findAll() (개선 전) | findAllWithTopics() - JOIN FETCH (개선 후) |
|------------|---------------------|---------------------------------------------|
| 레포 5개, Topic 5개 | 6번 (1 + 5) | 1번 |
| 레포 100개, Topic 20개 | 101번 (1 + 100) | 1번 |

**결론**: 데이터 규모와 무관하게 fetch join 적용 후 쿼리 수는 항상 1번으로
고정됨. 반면 개선 전에는 레포 개수에 정비례해서 쿼리가 증가하는 구조적
문제가 확인됨.

**해결**: JPQL에 JOIN FETCH를 명시해 Topic을 한 번의 SQL로 함께 조회하도록
변경. 단, 이 방식은 컬렉션 fetch join + 페이징 조합 시 메모리 페이징 문제가
발생하므로 페이징 없는 목록 조회에만 적용. 페이징이 필요한 검색 API는
아래 "동적 검색 조건 처리 및 컬렉션 fetch 전략" 참고.

**주의**: 실행 시간(ms) 단위의 성능 비교는 이번 테스트의 duration에 스프링
컨텍스트 로딩/스키마 재생성 시간이 포함되어 있어 신뢰할 수 없음. 실제 응답
시간 개선 수치는 Day32 JMeter 부하테스트에서 별도 실측 예정.

### 관련 테스트

- `GithubRepositoryTopicNPlusOneTest` / `GithubRepositoryTopicFetchJoinTest` — 소규모(레포 5개) 재현/해결
- `GithubRepositoryTopicNPlusOneScaleTest` / `GithubRepositoryTopicFetchJoinScaleTest` — 대량(레포 100개) 재현/해결

## 동적 검색 조건 처리 및 컬렉션 fetch 전략

**문제**: 검색 조건(keyword/language/topic/minStars)이 있을 수도 없을 수도 있어
동적 조합이 필요했음. JPQL 문자열 조합은 오타가 특정 조건 조합에서만
런타임에 드러나고, 리팩토링 시 IDE 지원을 못 받는 문제가 있었음.

**대안 비교**:
- JPQL 문자열 조합: 컴파일 타임 검증 불가
- Criteria API: 필드 접근이 여전히 문자열(`root.get("field")`)이라 근본 해결 안 됨
- QueryDSL: Q클래스가 컴파일 시점에 생성되어 필드 참조가 타입 안전해짐 → 채택

**해결**: `SearchCondition` record + `BooleanBuilder`로 null 조건을 자동 생략하도록
구성. topic 조건은 `topics.any()`로 처리해 컬렉션을 fetch하지 않고 EXISTS
서브쿼리로 변환되도록 함. 이 API는 페이징이 필요해서(검색 결과 규모 예측 불가)
메인 쿼리에 fetch join을 쓰지 않음 (컬렉션 fetch join + 페이징 조합은 위
N+1 해결 사례에서 확인했듯 Hibernate가 메모리 페이징을 하게 되는 위험이
있음). 대신 topics 필드에 `@BatchSize(size = 100)`을 적용.

**측정 결과**: 검색 결과 3건에 대해 topics를 LAZY로 순회 접근했을 때, 실제
SQL 로그 상 topics 조회 쿼리가 3번(N+1)이 아니라 `WHERE repo_id = any(?)`
형태의 배치 쿼리 1번으로 나감을 확인. 메인 검색 쿼리는 offset/limit이
정상적으로 SQL 레벨에서 걸림(컬렉션 join이 없어서 메모리 페이징 문제 없음).

### 관련 테스트

- `GithubRepositoryQueryRepositoryTest#search_결과_topics_LAZY_로딩_배치조회` — `@BatchSize` 배치 쿼리 검증

### 페이지네이션 검증

**문제**: 위 "메인 검색 쿼리는 offset/limit이 정상적으로 SQL 레벨에서 걸림"이라는
서술이 이를 뒷받침하는 테스트 없이 문서에만 적혀 있었음. QueryDSL
`BooleanBuilder`로 동적 조건을 조합하는 구조에서는 조건 분기 추가/수정 과정에서
페이징 파라미터(`offset`/`limit`)가 누락되거나 count 쿼리가 아예 안 나가는
실수가 흔히 발생할 수 있어, 별도 검증이 필요했음.

**검증 방법**: `GithubRepositoryQueryRepositoryPaginationTest`를 별도 클래스로
작성. 결과값 검증(`content` 크기, `totalElements`, `totalPages`)과 함께,
Logback `ListAppender`를 `org.hibernate.SQL` 로거에 붙여 `search()` 실행 중
실제로 나간 SQL 텍스트를 자동으로 캡처·검증하는 방식을 사용. 다음 케이스를
포함:
- 정상 페이징: 15건을 `size=5`로 조회
- 마지막 페이지 불균등 케이스: 17건을 `size=5`로 조회
- 빈 결과 케이스: 조건에 맞는 데이터가 없는 경우

**측정 결과 (SQL 로그 기반)**:
- 이 Hibernate 6.6 + PostgreSQL 15 조합은 `LIMIT`/`OFFSET`이 아니라 ANSI
  표준 구문 `order by ... offset ? rows fetch first ? rows only`를 생성함
- 컨텐츠 조회 쿼리와 count 쿼리가 분리되어 각각 나가며, 두 쿼리 모두
  `repo_topic` 조인이 섞여 있지 않음 (topics는 위 배치 쿼리 사례처럼 별도
  `@BatchSize` IN절로 처리됨)
- 마지막 페이지(17건, `size=5`, `page=3`) 요청 시 `offset=15`,
  `fetch first=5`로 바인딩되지만, 실제로는 남은 2건만 반환됨 — DB에
  `fetch first` 개수보다 적게 남으면 있는 만큼만 반환하는 정상 동작

**결론**: 페이지네이션은 DB 레벨에서 정상 동작하며 메모리 페이징이 아님이
SQL 로그로 확인됨. 위 측정 결과 서술의 "limit/offset" 표현은 실제 생성되는
구문에 맞춰 "offset/fetch"(ANSI 표준 구문)로 정정.

### 관련 테스트 (페이지네이션)

- `GithubRepositoryQueryRepositoryPaginationTest` — 9개 케이스 전부 통과,
  기존 테스트 회귀 없음

### DB 인덱스 적용 전/후 비교

**목적**: Day 9 QueryDSL 동적 검색 조건(`language`, `star_count`)에 대해, 인덱스
유무가 실제 쿼리 실행 방식과 성능에 어떤 영향을 주는지 실측 검증.

**측정 환경**: `github_repository` 테이블에 더미 데이터 100,000건(language 6종
균등분포, star_count 0~100000 랜덤)을 넣은 상태에서, 다음 단일 컬럼 인덱스
2개만 생성(복합 인덱스는 생성하지 않음):
```sql
CREATE INDEX idx_repo_language ON github_repository (language);
CREATE INDEX idx_repo_star_count ON github_repository (star_count);
```

**결과**:

| 쿼리 | 인덱스 전 스캔방식 | 인덱스 전 Rows Removed by Filter | 인덱스 후 스캔방식 | 인덱스 후 Filter |
|---|---|---|---|---|
| language='Java' | Seq Scan | 83208 | Bitmap Heap Scan (+ Bitmap Index Scan) | 없음 (Index Cond) |
| star_count>90000 | Seq Scan | 89901 | Bitmap Heap Scan (+ Bitmap Index Scan) | 없음 (Index Cond) |
| language='Java' AND star_count>90000 | Seq Scan | 98339 | BitmapAnd (두 인덱스 결합) → Bitmap Heap Scan | 없음 (Recheck Cond) |

**실측 Execution Time (ms)**:

| 쿼리 | 인덱스 전 Run1 | 인덱스 전 Run2 | 인덱스 후 |
|---|---|---|---|
| language='Java' | 20.047 | 37.792 | 7.566 |
| star_count>90000 | 12.266 | 10.110 | 11.114 |
| language='Java' AND star_count>90000 | 11.898 | 50.077 | 8.512 |

인덱스 전 값은 `kubectl exec`로 파드 내 psql을 매번 새 프로세스로 띄워 측정한
값이고, 인덱스 후 값은 GUI SQL 클라이언트로 이미 연결된 세션에서 직접 측정한
값이다. `kubectl exec`는 호출마다 새 exec 세션을 띄우는 오버헤드가 섞여
편차가 컸던 반면(같은 쿼리가 인덱스 전 상태에서도 Run1/Run2 간 최대 4배
차이), GUI 클라이언트 측정값은 세 쿼리 모두 5.8~11.1ms 사이로 훨씬
안정적이었음. 두 측정 모두 서버(PostgreSQL)가 계산하는 `Execution Time`
자체는 동일한 지표이므로, 쿼리 플랜(Bitmap Heap Scan, BitmapAnd 등)이
동일한 이상 GUI 클라이언트로 측정한 값이 노이즈가 적은 값으로 판단해 채택함.

**왜 Index Scan이 아니라 Bitmap Heap Scan인가**: `language='Java'`(16.8%),
`star_count>90000`(10.1%)처럼 반환 비율이 중간 수준일 때는 순수 Index Scan(랜덤
I/O 다수 발생)보다, 인덱스로 비트맵을 먼저 만들고 힙을 페이지 순서로 훑는
Bitmap Heap Scan이 더 효율적이라고 옵티마이저가 판단함. Filter가 Index
Cond/Recheck Cond로 대체되어, 조건에 안 맞는 행을 읽고 버리는 과정 자체가 사라짐.

**복합 조건에서 단일 인덱스 조합이 어떻게 동작했는가**: `BitmapAnd`로
`idx_repo_language`, `idx_repo_star_count` 각각의 비트맵을 만든 뒤 교집합(1661건)만
골라서 힙을 읽음. 복합 인덱스(`language, star_count`) 없이도 단일 컬럼 인덱스
2개 조합만으로 Filter 없는 효율적인 조회가 가능함을 확인. 단, 이번 조건은
language의 선택도가 낮지 않아(16.8%) BitmapAnd 비용이 크지 않았던 것으로,
조건 선택도가 다른 경우(예: 두 조건 각각은 안 희소한데 교집합만 극도로
희소한 경우) 복합 인덱스가 유리할 수 있어 결론을 일반화하지 않음.

**실행시간(ms)에 대한 메모**: 인덱스 전(`kubectl exec` 기반) 측정은 같은 쿼리도
Run1/Run2 간 최대 4배 가까이 편차가 있었던 반면, 인덱스 후(GUI 클라이언트
직접 측정) 값은 세 쿼리 모두 5.8~11.1ms 사이로 훨씬 안정적이었음. 그럼에도
절대 시간 수치는 측정 도구/세션에 따라 달라질 수 있어 참고용으로만 기록.
핵심 근거는 스캔 방식 변화(Seq Scan→Bitmap Heap Scan)와 Rows Removed by
Filter가 완전히 사라진 것(인덱스가 실제로 활용되고 있다는 가장 신뢰할 수
있는 증거). 100,000행 규모는 측정 방식에 따른 편차가 인덱스 유무 자체보다
크게 보일 수 있는 구간이라는 점을
명시.

**결론**: 인덱스 적용으로 Seq Scan(전체 스캔+필터링)에서 Bitmap Heap
Scan/BitmapAnd(조건에 맞는 행만 직접 조회)로 전환됨을 확인. 단일 컬럼 인덱스
2개 조합만으로 동적 검색 조건(Day 9 QueryDSL)의 다양한 조합에 유연하게 대응
가능.

## Flyway 마이그레이션 도입

**문제/배경**: `ddl-auto: create-drop` 방식으로 스키마를 운영해오면서, Spring
Boot 앱이 재시작될 때마다 Hibernate가 스키마를 drop 후 재생성하는 문제가
있었음. Day 10 인덱스 실측 도중 앱이 꺼진 상태에서 테이블 자체가 없어서
raw DDL로 스키마를 임시로 재현해야 했던 사례가 실제 계기가 됨. 원래 계획은
4주차(pgvector 전환 시) Flyway 도입이었으나, 그 시점엔 테이블이 더 많고
실데이터도 쌓여 스키마 역산이 어려워지므로 2주차로 조기 도입.

**적용 내용**:
- `build.gradle`: `flyway-core`, `flyway-database-postgresql` 의존성 추가
- `application.yaml`: `ddl-auto`를 `create-drop` → `validate`로 전환
- `src/main/resources/db/migration/V1__init.sql` 작성
  - 엔티티 8개(GithubRepository, Topic, Issue, TrendScore, RepoEmbedding,
    User, UserFavorite, UserSkill) + `@ManyToMany` 중간테이블 `repo_topic`,
    총 9개 물리 테이블의 `CREATE TABLE`
  - FK 제약 9개, UNIQUE 제약 7개, CHECK 제약 2개(`process_status`,
    `issues.state` — 각 엔티티 실제 enum 값과 대조 검증 완료), `repo_topic`
    복합 PK(엔티티 애노테이션엔 없으나 중복 방지 목적으로 추가)
  - Day 10에서 실측 검증된 인덱스 2개(`idx_repo_language`,
    `idx_repo_star_count`) 포함
- `V1__init.sql`은 스키마(DDL)만 포함하며 데이터 INSERT는 없음 (적용 직후
  9개 테이블은 모두 빈 상태)

**트러블슈팅 - 최초 적용 시도 실패**:
- 문제: `Found non-empty schema(s) "public" but no schema history table.`
  에러 발생
- 원인: 라이브 DB에 이전(Day 10) raw DDL로 만든 `github_repository` 테이블이
  삭제 안 된 채 남아있었음. Flyway는 `flyway_schema_history` 기록 없이
  기존 스키마가 존재하면, 의도치 않은 덮어쓰기를 막기 위해 실행을 중단함
- 해결: `baselineOnMigrate` 같은 우회 설정을 쓰지 않고, 남은 테이블을
  완전히 삭제한 뒤 재시도 (`DROP TABLE ... CASCADE`로 완전히 빈 스키마
  확보 후 재실행)

**검증 결과**:
- 1차 실행: 로그에 `Migrating schema "public" to version "1 - init"`
  → `Successfully applied 1 migration` 확인
- `ddl-auto: validate` 통과, 앱 정상 기동
- DB 확인: `flyway_schema_history` 테이블 생성(version=1, description=init,
  script=V1__init.sql, success=true) + 9개 테이블 전부 생성 +
  `github_repository`에 인덱스 2개/CHECK/FK 정상 부착
- 앱 재시작 검증(핵심): 앱을 껐다 다시 켰을 때, 로그에 `Migrating schema`
  문구 없이 `Current version of schema "public": 1`만 확인하고 통과
  → Flyway가 V1을 재실행하지 않고 스킵함을 확인
- 결론: 이전에는 앱 재시작만으로 스키마가 사라지던 문제가, Flyway 도입
  후에는 재현되지 않음을 실측으로 증명

**알아두어야 할 한계 (참고 메모)**:
- 현재 kind 매니페스트(`k8s/base/postgres-deployment.yaml`)의 PostgreSQL
  볼륨은 `emptyDir`로 설정되어 있어, Flyway는 "Spring Boot 앱 재시작"으로
  인한 스키마 유실만 방지하며, PostgreSQL Pod 자체가 재생성되는 경우
  (emptyDir 초기화)에는 `flyway_schema_history`를 포함한 모든 데이터가
  여전히 유실됨
- 이 경우 Flyway는 V1부터 재실행해 스키마(빈 테이블)는 복구하지만
  데이터는 복구하지 못함
- 실제 데이터 보존이 필요한 시점(운영 배포 등)에는 PersistentVolumeClaim
  전환이 필요하며, 이는 현재 매니페스트에 주석으로 계획되어 있음
  (아직 미적용 상태)

**추가 발견 및 조치 (V2)**: Day 9~10에서 실제 쿼리 패턴과 인덱스 커버리지를
전수 대조한 결과, `IssueJpaRepository.findByRepositoryId()`가 조회하는
`issues.repo_id` 컬럼에 인덱스가 없음을 확인. FK 제약은 있었으나 PostgreSQL은
FK에 자동으로 인덱스를 만들지 않으므로 Seq Scan이 되는 공백이었음.
`V2__add_issues_repo_id_index.sql`로 `idx_issues_repo_id` 인덱스를 추가
(V1은 수정하지 않음). Flyway 로그상 V1은 재실행 없이 스킵되고 V2만 적용됨을
확인(`Current version: 1` → `Migrating ... version "2"` →
`Successfully applied 1 migration`). `flyway_schema_history`에 version 1, 2
모두 success=true로 기록됨. 대량 데이터 기반 Seq Scan→Index Scan 전환
실측은 진행하지 않음(추후 실데이터 축적 시 확인 예정).

## Redis 도입 (트렌드 랭킹 + 중복 수집 방지)

**배경**: 두 가지 문제를 해결해야 했음
1. 트렌드 랭킹을 매 요청마다 PostgreSQL에서 `ORDER BY`로 재계산하는 비효율
2. 다중 스레드/Consumer 환경에서 동일 레포지토리를 중복 수집할 수 있는
   경쟁 상태(Race Condition, TOCTOU: Time-Of-Check to Time-Of-Use)

### 트렌드 랭킹 - Sorted Set 채택

**대안 비교**:
- 일반 캐시(String/Hash)에 정렬 리스트를 통째로 저장: 순위 변경 시 전체
  재구성 필요(O(N)), 개별 레포 순위 조회도 O(N)
- Redis Sorted Set: `ZINCRBY`로 개별 member 점수만 갱신해도 내부적으로
  정렬 상태 자동 유지(갱신 O(log N)), 상위 N개 조회(`ZREVRANGE`)도
  O(log N + N)

**구현**: `TrendService.increaseScore(Long repoId, double amount)`는
`opsForZSet().incrementScore()`로 점수 누적. `getTopRepos(int count)`는
`opsForZSet().reverseRangeWithScores()`로 상위 N개 조회 후 `List<Long>`으로
변환. member 파싱 실패(`NumberFormatException`) 시 해당 항목만 로그 남기고
스킵하도록 방어 처리(오염 데이터 하나로 전체 API가 죽는 것 방지).

**측정(정렬 검증 테스트)**: 테스트 전용 repoId 3건(9001L, 9002L, 9003L)에 각각
`increaseScore`로 점수 100, 250, 80을 부여한 뒤 `getTopRepos(3)` 호출 결과가
`[9002, 9001, 9003]`(점수 250 > 100 > 80) 순서와 정확히 일치함을 확인
(`containsExactly`로 순서까지 검증). 이후 최하위였던 9003에 `increaseScore(9003L,
50)`을 추가 반영(80→130)해 9001(100)을 추월시킨 뒤 재조회한 결과, 순서가
`[9002, 9003, 9001]`로 실제 역전됨을 확인.

| 테스트 케이스 | 결과 | 소요 시간 |
|---|---|---|
| `getTopRepos_점수_내림차순_정렬` | PASS | 0.428s |
| `getTopRepos_점수_역전_시_순위도_역전` | PASS | 0.139s |

(`TrendServiceTest`, 2건 모두 PASS, 전체 소요 0.574s — 테스트용 member는
`@AfterEach`에서 `opsForZSet().remove`로 3건만 개별 삭제해 실제 운영 데이터에
영향 없음을 `ZSCORE` 조회로 재확인)

### 중복 수집 방지 - setIfAbsent(SET NX) 기반 분산 락

**원인**: "확인(조회)"과 "실행(저장)"을 별도 연산으로 분리하면(예: DB
SELECT 후 INSERT), 그 사이 다른 스레드가 끼어들어 동일 대상을 중복
처리할 여지가 생김. DB UNIQUE 제약 + 예외 처리로도 막을 수 있으나, 이
방식은 스케줄러가 반복 호출하는 가벼운 체크 로직치고 트랜잭션/예외
비용이 무거움.

**해결**: `opsForValue().setIfAbsent(key, value, ttl)`로 확인+점유를 Redis
서버 내 단일 원자적 명령으로 처리(Redis는 싱글 스레드로 명령을 처리하므로
해당 명령 실행 도중 다른 요청이 끼어들 수 없음). TTL(10분)을 부여해
비정상 상황에서 락이 영구적으로 안 풀리는 문제를 방지.

**설계 변경**: 최초 `tryLock(Long repoId)`로 설계했으나, 이후 파이프라인
설계(스케줄러 → Kafka Consumer가 신규 레포를 최초 처리하는 시점)를
재검토한 결과, 신규 레포는 DB 저장 전이라 PK(repoId)가 아직 발급되지
않은 상태일 수 있음을 확인. `tryLock`/`releaseLock`의 파라미터를
`String identifier`로 변경해, GitHub의 fullName처럼 PK 생성 여부와
무관하게 항상 존재하는 식별자를 기준으로 락을 걸도록 재설계.

**측정(동시성 검증 테스트)**: `ExecutorService`(고정 스레드풀 10개)와
`CountDownLatch` 3개(스레드 10개 전원의 준비 완료 확인용 `readyLatch`, 동시
시작 신호용 `startLatch`, 전원 종료 대기용 `doneLatch`)로 모든 스레드가
`tryLock()` 진입 직전까지 도달한 걸 확인한 뒤 동시에 시작하도록 동기화.
동일 identifier(`"test-repo-concurrency"`)로 10개 스레드가 동시에 락 획득을
시도한 결과, `AtomicInteger`로 집계한 성공(true) 횟수가 정확히 **1**, 나머지
**9**개는 실패(false)로 처리됨을 확인(`assertThat(successCount.get()).isEqualTo(1)`
통과). 실패한 9개 스레드 각각에서 `log.info("identifier=... 이미 처리 중")`이
남아 실패 처리 경로도 정상 동작함을 로그로 교차 검증.

`DuplicateCheckServiceTest`, 1건 PASS, 소요 시간 4.241s (스레드 10개가 동시에
Redis `SETNX` 명령을 보내는 네트워크 왕복이 포함된 시간). 테스트 종료 후
`@AfterEach`의 `releaseLock`으로 `lock:repo:test-repo-concurrency` 키를
삭제했고, `EXISTS` 조회로 키가 실제로 안 남아있음을 재확인.

### 캐싱 적용 범위 - @Cacheable 조건부 필터링 대신 TTL 활용

**쟁점**: `GithubRepositoryService.getById(Long id)`에 조건 없이 `@Cacheable`을
적용하면 "조회된 모든 id"가 캐시에 들어가, 애초 의도했던 "인기 레포만
캐싱"과 어긋나는 것 아닌가 하는 문제 제기.

**검토한 대안**:
- 트렌드 상위 N개에 대해 별도 배치/스케줄러로 캐시 워밍: 캐싱 로직과
  별개의 코드 경로가 추가로 필요해 복잡도 증가
- `@Cacheable`의 `condition` 속성으로 SpEL 기반 조건부 캐싱: 다른 빈을
  참조해 "인기 여부"를 매 호출마다 재판단해야 해 오히려 로직이 꼬임

**결정**: 별도 필터링 로직을 두지 않고 TTL(1시간)만 부여. 실제로 자주
조회되는(인기 있는) id는 캐시 히트가 반복되고, 어쩌다 조회되는 롱테일
id는 TTL 경과 후 자연 소멸하므로, 별도 조건 로직 없이도 동일한 효과를
더 단순한 구조로 달성.

**구현**: `RedisConfig`에 `RedisCacheManager` 빈 등록
(`RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(1))`,
`GenericJackson2JsonRedisSerializer`로 값 직렬화 - JSON 형태로 저장돼
redis-cli로 직접 확인 가능). `getById`에 `@Cacheable(value="popularRepos",
key="#id")`, `delete`에 `@CacheEvict(value="popularRepos", key="#id")` 적용.
`save()`는 파라미터에 id가 없고 신규 생성이라 캐시 무효화 대상 자체가
없어 대상에서 제외. star_count 갱신 시점의 `@CacheEvict` 연동은 해당
갱신 메서드 자체가 아직 없어(추후 GitHub API 폴링 파이프라인 구현 시
함께 추가 예정) 이번 범위에서 제외.

### 트러블슈팅 - RedisAutoConfiguration exclude 잔존

**문제**: `RedisConfig` 작성 전 확인 과정에서 `application.yml`의
`autoconfigure.exclude` 목록에 `RedisAutoConfiguration`이 여전히 제외
처리된 채로 남아있는 것을 발견(과거 Redis 미연동 시기에 걸어둔 설정이
제거되지 않고 방치됨). 이 상태로 `RedisConfig`를 추가하면
`RedisConnectionFactory` 빈 자체가 생성되지 않아 기동 실패로 이어질
가능성이 있었음.

**해결**: exclude 라인 제거 후 재기동, Redis 관련 자동설정이 정상
활성화됨을 로그로 확인(Bootstrapping Spring Data Redis repositories).

### 관련 테스트

- `TrendServiceTest` — 2건 PASS(전체 0.574s). Sorted Set 점수 갱신
  (100/250/80 → 9002/9001/9003 순서) 및 점수 역전(9003 +50 → 9002/9003/9001
  순서로 변경) 검증
- `DuplicateCheckServiceTest` — 1건 PASS(4.241s). 스레드 10개 동시 요청 시
  락 획득 성공 1건/실패 9건(`AtomicInteger` 집계 기준) 검증
