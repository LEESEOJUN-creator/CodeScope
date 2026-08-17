# Troubleshooting

## 목차

- [N+1 문제 재현 및 해결 (Fetch Join)](#n1-문제-재현-및-해결-fetch-join)
- [동적 검색 조건 처리 및 컬렉션 fetch 전략](#동적-검색-조건-처리-및-컬렉션-fetch-전략)
- [Flyway 마이그레이션 도입](#flyway-마이그레이션-도입)
- [Redis 도입 (트렌드 랭킹 + 중복 수집 방지)](#redis-도입-트렌드-랭킹--중복-수집-방지)
- [GitHub OAuth + JWT + Redis Refresh Token Rotation](#github-oauth--jwt--redis-refresh-token-rotation)
- [DB 세마포어 배압 제어 실측](#db-세마포어-배압-제어-실측)
- [가상 스레드 Pinning 실측 (JFR jdk.VirtualThreadPinned)](#가상-스레드-pinning-실측-jfr-jdkvirtualthreadpinned)
- [Kafka 파이프라인 인프라·연동 트러블슈팅](#kafka-파이프라인-인프라연동-트러블슈팅)
  1. [bitnami/kafka Docker Hub 정책 변경으로 인한 ImagePullBackOff](#1-bitnamikafka-docker-hub-정책-변경으로-인한-imagepullbackoff)
  2. [Kafka 브로커 OOMKilled (메모리 512Mi 부족)](#2-kafka-브로커-oomkilled-메모리-512mi-부족)
  3. [`__consumer_offsets` 내부 토픽 replication factor 불일치](#3-__consumer_offsets-내부-토픽-replication-factor-불일치)
  4. [로컬 개발 환경에서 K8s 내부 Kafka 접근 시 advertised.listeners DNS 해석 실패](#4-로컬-개발-환경에서-k8s-내부-kafka-접근-시-advertisedlisteners-dns-해석-실패)
  5. [GitHub Personal Access Token 만료로 인한 401 Bad Credentials](#5-github-personal-access-token-만료로-인한-401-bad-credentials)
  6. [Kafka 컨슈머 커밋 로직의 finally 블록 오류](#6-kafka-컨슈머-커밋-로직의-finally-블록-오류)
- [EmbedConsumer 구조적 한계 — poll 스레드 동기 처리로 인한 리밸런싱](#embedconsumer-구조적-한계--poll-스레드-동기-처리로-인한-리밸런싱)
- [EmbedConsumer poll 스레드 분리 + RestClient 타임아웃 실측 검증 (근본 리팩토링)](#embedconsumer-poll-스레드-분리--restclient-타임아웃-실측-검증-근본-리팩토링)
- [RAG 레포 추천(RepoRecommendService) 구현 및 llama3.2:3b 반복 생성 열화 실측](#rag-레포-추천repo-recommendservice-구현-및-llama323b-반복-생성-열화-실측)
- [nomic-embed-text 한국어 검색 한계 발견](#nomic-embed-text-한국어-검색-한계-발견)

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
시간 개선 수치는 JMeter 부하테스트에서 별도 실측 예정.

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

### DB 인덱스 적용 전/후 비교

**목적**: QueryDSL 동적 검색 조건(`language`, `star_count`)에 대해, 인덱스
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
2개 조합만으로 동적 검색 조건(QueryDSL)의 다양한 조합에 유연하게 대응
가능.

## Flyway 마이그레이션 도입

**문제/배경**: `ddl-auto: create-drop` 방식으로 스키마를 운영해오면서, Spring
Boot 앱이 재시작될 때마다 Hibernate가 스키마를 drop 후 재생성하는 문제가
있었음. 인덱스 실측 도중 앱이 꺼진 상태에서 테이블 자체가 없어서
raw DDL로 스키마를 임시로 재현해야 했던 사례가 실제 계기가 됨. 원래 계획은
pgvector 전환 시점의 Flyway 도입이었으나, 그 시점엔 테이블이 더 많고
실데이터도 쌓여 스키마 역산이 어려워지므로 조기 도입.

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
  - 실측 검증된 인덱스 2개(`idx_repo_language`,
    `idx_repo_star_count`) 포함
- `V1__init.sql`은 스키마(DDL)만 포함하며 데이터 INSERT는 없음 (적용 직후
  9개 테이블은 모두 빈 상태)

**트러블슈팅 - 최초 적용 시도 실패**:
- 문제: `Found non-empty schema(s) "public" but no schema history table.`
  에러 발생
- 원인: 라이브 DB에 이전에 raw DDL로 만든 `github_repository` 테이블이
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

**추가 발견 및 조치 (V2)**: 실제 쿼리 패턴과 인덱스 커버리지를
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

## GitHub OAuth + JWT + Redis Refresh Token Rotation

### Refresh Token 회전 시 조회·삭제 비원자성 (TOCTOU race condition)

**문제**: `validateAndRotate`에서 Redis `get()`과 `delete()`를 두 번의 별도
호출로 처리.

**원인**: 두 호출 사이의 시간 간격에 동시 요청이 끼어들면 둘 다 검증을
통과해 이중 회전이 발생. 먼저 발급된 토큰이 나중 요청에 덮어써져 정상
사용자가 도난으로 오탐되는 false positive가 발생할 수 있음.

**해결**: Redis `GETDEL`(Spring Data Redis의 `opsForValue().getAndDelete()`)로
교체해 조회+삭제를 원자적으로 처리. 불일치 분기의 중복 `delete()` 호출도
함께 제거.

**참고**: `DuplicateCheckService`의 `setIfAbsent`(SET NX)와 동일 원칙
("확인+점유는 원자적으로").

**트레이드오프**: `getAndDelete`는 조회 시점에 무조건 삭제되므로, 새 토큰
발급 전 예외가 발생하면 토큰이 유실되어 재로그인이 필요함. 원자성 확보를
우선한 선택.

### HS256 서명 알고리즘이 강제되지 않음

**문제**: `signWith(secretKey)`만 호출해 알고리즘을 명시하지 않음.

**원인**: `Keys.hmacShaKeyFor()`는 키 길이에 따라 JCA 알고리즘을 자동
선택(256bit→HmacSHA256, 384bit→HmacSHA384, 512bit→HmacSHA512).
`JWT_SECRET`을 더 긴 값으로 교체하면 코드 변경 없이 서명 알고리즘이 조용히
바뀌는 문제가 있음.

**해결**: `signWith(secretKey, Jwts.SIG.HS256)`으로 명시해 키 길이와
무관하게 알고리즘을 고정.

### User 생성 시 동시성 충돌

**문제**: `findOrCreateByGithub`이 `findByGithubId` 조회 후 없으면 `save`
하는 구조라, 같은 `githubId`로 동시 요청 시 둘 다 조회에서 못 찾고 둘 다
`save`를 시도.

**원인**: `github_id`의 unique 제약으로 두 번째 저장이 거부되며
`DataIntegrityViolationException`이 발생. 잡는 코드가 없어 500으로 떨어짐.

**해결**: `save`를 try-catch로 감싸 `DataIntegrityViolationException` 발생
시 `findByGithubId`로 재조회해 반환. DB의 unique 제약을 원자적 방어선으로
활용.

**참고**: `User`가 `GenerationType.IDENTITY`라 `save()` 시점에 즉시 INSERT가
실행되어 이 지점에서 예외를 잡을 수 있음.

### 쓰기 API가 인증 없이 열려있음

**문제**: `SecurityConfig`에서 `/api/repos/**` 전체를 `permitAll`로 설정해
POST/DELETE까지 비로그인 사용자가 호출 가능. 누구나 레포를 삭제할 수 있는
상태였음.

**원인**: 조회 API 공개 목적으로 경로 단위 `permitAll`을 적용하면서 HTTP
메서드 구분을 하지 않음.

**해결**: `requestMatchers(HttpMethod.GET, "/api/repos/**")`만 `permitAll`로
두고, POST/PUT/PATCH/DELETE는 `authenticated()`로 분리. 쓰기 규칙을 먼저
배치.

**검증**: 토큰 없이 POST/DELETE 호출 시 401, Authorize 후 재호출 시 정상
처리 확인.

### 미인증 요청에 401 대신 리다이렉트 응답 위험

**문제**: `oauth2Login()`이 등록된 상태에서 미인증 요청이 `authenticated()`
경로에 들어오면 Spring Security 기본 동작으로 `/oauth2/authorization/github`로
302 리다이렉트될 수 있음. REST API에서 프론트가 JSON 대신 리다이렉트를
받게 됨.

**원인**: 커스텀 `authenticationEntryPoint` 미설정.

**해결**: `JwtAuthenticationEntryPoint`를 별도 클래스로 생성해
`exceptionHandling().authenticationEntryPoint(...)`에 연결.
`ApiResponse.error()` + 401 JSON으로 응답.

**참고**: `accessDeniedHandler`(403)는 현재 `ROLE_USER` 단일 권한이고
`hasRole` 기반 분기가 없어 도달 불가능한 경로이므로 의도적으로 생략.

### 쿠키 secure 속성 하드코딩

**문제**: `RefreshTokenCookieFactory`에 `secure(false)`가 코드에 박혀 있어
HTTPS 배포 시 변경을 누락하면 토큰이 평문 전송 위험에 노출됨.

**원인**: 로컬 http 환경에서 `secure(true)`면 브라우저가 쿠키를 저장/전송하지
않아 임시로 false 고정.

**해결**: `@Value("${jwt.cookie-secure:false}")`로 생성자 주입,
`application.yaml`에 `${JWT_COOKIE_SECURE:false}` 추가해 환경변수로 제어.

### 설계 결정 / 알려진 제약 (트러블슈팅 아님)

위 6건과 달리 버그가 아니라 의도적으로 선택한 사항:

- **Refresh Token 1세션 정책**: key가 `refresh:user:{userId}` 하나라 유저당
  1개만 유지. 다른 기기 로그인 시 기존 세션이 무효화됨. 멀티 디바이스 지원이
  필요하면 key에 deviceId를 추가하는 방식으로 확장 가능.
- **로그인 성공 응답 방식**: 현재 `GithubOAuth2LoginSuccessHandler`가 JSON을
  직접 응답 바디에 작성. 프론트엔드 미구현 상태에서 백엔드 검증 편의를 위한
  선택이며, 프론트 연동 시 `sendRedirect`로 전환 예정.

### 검증 결과

자동화 테스트가 아닌 Swagger UI를 통한 수동 요청으로 확인한 항목:

- GitHub OAuth 로그인 성공 → `users` 테이블에 User 생성 확인(`githubId`,
  `email`, `username`, `profileImageUrl` 정상 저장)
- Access Token payload 검증: `userId` 포함, `exp - iat = 900초`(15분) 일치,
  header `alg = HS256` 확인
- `refreshToken` 쿠키 속성 확인: `HttpOnly` 적용, `SameSite=Lax`, `Path=/`,
  만료 2주 후로 설정됨
- `POST /api/auth/refresh` 정상 동작: 200 + 새 accessToken 발급 확인
- 소모된 Refresh Token 재사용 시도 → 401 반환 확인(Rotation 재사용 감지
  동작)
- 인증 없이 쓰기 API 호출 → 401 JSON 반환, 인증 후 재호출 → 정상 처리 확인

**미측정 항목**: `GET /api/repos/trending`의 쿼리 수 비교(`fetchByLoop` vs
`fetchByBatch`)는 Redis Sorted Set(`trending:repos`)에 실제 데이터가 없어
측정 보류. Kafka 수집 파이프라인 구축 후 실제 데이터로 측정
예정.

## DB 세마포어 배압 제어 실측

**1. 배경**: 가상 스레드가 HikariCP `maximum-pool-size`(10)를 초과해 동시에
커넥션을 요구하면 `SQLTransientConnectionException`이 발생할 위험이 있어,
`DbSemaphoreConfig`(permits = `maximumPoolSize - reserve`)를 만들고
`DbBackpressureLoadTest`로 효과를 실측 검증했다.

**2. 1차 실측 (LOAD_SIZE=500, REPEAT_PER_THREAD=5)**

| | success | transientConnFail | pendingMax | elapsedMs |
|---|---|---|---|---|
| 세마포어 없음 | 500 | 0 | 490 | 15339 |
| 세마포어 있음 | 500 | 0 | 0 | 16087 |

이 시점엔 예외는 0건이었으나 `pendingMax=490`으로 "실패는 안 났지만
위험한 대기 상태"였음이 `HikariPoolMXBean.getThreadsAwaitingConnection()`
지표로 확인됨. 예외 건수만으론 보이지 않는 위험을 이 지표가 드러냄.

**3. 2차 실측 (`REPEAT_PER_THREAD`를 5→20으로만 변경, LOAD_SIZE=500 유지)**

세마포어 없음: `transientConnFail` 여전히 0건, `elapsedMs` 15.3s → 53.1s로
증가.

`REPEAT_PER_THREAD`를 늘리는 것은 "커넥션 점유 시간"을 늘리는 것이라
대기자 수 자체가 늘지 않아 30초 타임아웃 벽에 부딪히지 않았음을 확인.
이후 LOAD_SIZE(동시 요청 수) 증가가 더 적절한 변수라고 판단해 방향 전환.

**4. 3차 실측 (LOAD_SIZE=2000, REPEAT_PER_THREAD=5로 복귀)**

| | success | transientConnFail | otherFail | pendingMax | elapsedMs |
|---|---|---|---|---|---|
| 세마포어 없음 | 1227 | 773 | 0 | 1991 | 62476 |
| 세마포어 있음 | 2000 | 0 | 0 | 1 | 12561 |

실패율 39%(773/2000)가 세마포어 적용으로 0%가 됨.

예상외 결과: `elapsedMs`도 세마포어 적용 시 62476ms → 12561ms로 약 5배
단축됨. 세마포어 없는 쪽은 각 요청이 30초 타임아웃을 기다리다 실패하는
비용 자체가 컸던 것으로 해석됨(실패가 비용을 증가시킴).

**5. 결론**

- 세마포어 도입으로 실패율 39% → 0%, 총 처리 시간도 오히려 단축.
- "안전성과 처리량은 트레이드오프"라는 가설이 이 실측에서는 기각됨.
- `pendingMax` 지표가 예외 건수보다 먼저 위험 신호를 드러낸다는 것도
  1차 실측에서 확인됨.

## 가상 스레드 Pinning 실측 (JFR jdk.VirtualThreadPinned)

**배경**: `DbBackpressureLoadTest`(세마포어 없이 가상 스레드 다수가 동시에
`findById`를 호출하는 부하 테스트)를 돌리는 과정에서, 가상 스레드가
synchronized 블록 안에서 park되면 캐리어 스레드까지 함께 묶이는 "pinning"
현상이 실제로 발생하는지 JFR로 검증.

**1. 가설**: pgjdbc(PostgreSQL JDBC 드라이버) 내부 synchronized 블록에서
가상 스레드 pinning이 발생할 것이다.

**2. 1차 실측 (threshold=20ms, `settings=profile` 기본값)**

`build.gradle`에 `dbBackpressureJfrTest` 태스크를 추가하고
`-XX:StartFlightRecording=filename=build/pinning.jfr,settings=profile`로
`세마포어_없이_실행` 테스트를 실행.

- 결과: `jdk.VirtualThreadPinned` **0건**
- 문제: `profile.jfc`의 `jdk.VirtualThreadPinned` 기본 threshold가 20ms라,
  이 시점의 0건이 "pinning이 없다"인지 "20ms보다 짧아서 안 걸렸다"인지
  구분할 수 없었음.

**3. 2차 실측 (threshold=0ms로 낮춤)**

JFR 옵션을 `settings=profile,jdk.VirtualThreadPinned#threshold=0ms`로
변경해 동일 조건(세마포어 없음)으로 재실행.

- 결과: `jdk.VirtualThreadPinned` **429건** 발생
  (duration 0.008ms ~ 9ms, 전부 20ms 미만)
- 확인된 것: 1차에서 0건이 나온 이유는 pinning이 아예 없어서가 아니라,
  threshold 20ms에 전부 걸러졌기 때문이었음.

**4. 원인 분석 (스택 트레이스)**

`jfr print --events jdk.VirtualThreadPinned --stack-depth 64 build/pinning.jfr`로
429건의 전체 스택을 확인.

- 429건 전부 동일한 경로였음:
  `org.hibernate.engine.jdbc.spi.SqlStatementLogger.logStatement()` →
  `java.io.PrintStream` / `sun.nio.cs.StreamEncoder`(synchronized 메서드) →
  `LockSupport.park()` → `VirtualThread.parkOnCarrierThread()`
- `org.postgresql`(pgjdbc) 패키지는 429건의 스택 어디에도 **0회** 등장.
- 즉 pinning의 유발 지점은 pgjdbc가 아니라, Hibernate가 실행한 SQL을
  콘솔에 `println`으로 찍는 로깅 경로(JDK `PrintStream`/`StreamEncoder`의
  synchronized 메서드)였음.

**5. 확정 실측 (SQL 로깅 끄고 재실행)**

`application.yaml`을 확인한 결과 다음 두 설정이 모두 켜져 있었음:
```yaml
spring.jpa.show-sql: true                  # line 55
logging.level.org.hibernate.SQL: DEBUG      # line 193
```
이 SQL 로깅이 원인으로 지목되어, `application.yaml`(프로덕션 설정)은
건드리지 않고 `dbBackpressureJfrTest` 태스크에만 실행 시점 System
property로 override:
```
-Dspring.jpa.show-sql=false
-Dlogging.level.org.hibernate.SQL=WARN
```
threshold=0ms JFR 설정은 그대로 둔 채 `세마포어_없이_실행`을 재실행.

- 결과: `jdk.VirtualThreadPinned` **0건**
- 결론: SQL 로깅을 끄자 429건이 전부 사라짐 → 원인이 SQL 로깅이었음이
  확정됨.

**6. 결론 및 실무 시사점**

- pgjdbc 자체는 이번 워크로드(가상 스레드 2000개, `REPEAT_PER_THREAD=5`,
  세마포어 없음) 범위에서 가상 스레드 pinning을 유발하지 않음이 실측으로
  확인됨.
- 실제 pinning 원인은 pgjdbc가 아니라 개발 편의용 SQL 로깅
  (`show-sql`/`org.hibernate.SQL` DEBUG의 `println` 기반 출력 경로)이었음.
- 운영 환경에서 SQL 로깅을 끄거나 최소화하는 것은, 기존에는 "로그 양이
  많아서"라는 이유만 있었다면, 이제 "가상 스레드 pinning을 유발할 수
  있다"는 근거가 추가됨.

## Kafka 파이프라인 인프라·연동 트러블슈팅

k8s(kind) 위에 Kafka를 직접 올려 로컬 개발 환경에서 붙이는 과정부터,
`GithubApiClient` → `CollectProducer` → `CollectConsumer` → `EmbedProducer`로
이어지는 수집 파이프라인을 구현하며 실제로 실행해 확인한 문제 6건을 기록한다.
(코드 리뷰/설정 검토 단계에서만 다룬, 실행으로 검증하지 않은 이슈는 이
문서에 포함하지 않는다.)

### 1. bitnami/kafka Docker Hub 정책 변경으로 인한 ImagePullBackOff

**증상**: Kafka StatefulSet 배포 시 `ImagePullBackOff` 발생.
`kubectl describe pod` 결과:
```
docker.io/bitnami/kafka:3.7: not found
```

**진단 과정**: `kubectl describe pod kafka-0`으로 이벤트 로그 확인 →
이미지 태그 자체가 레지스트리에서 사라진 상태임을 확인. Docker Hub에서
`bitnami/kafka` 태그 목록을 직접 조회해 해당 버전이 더 이상 무료 태그로
제공되지 않음을 확인.

**원인**: bitnami가 Docker Hub 무료 배포 정책을 변경해 특정 버전 태그가
제거됨(유료 Bitnami Secure Images 전용으로 전환).

**해결**: `apache/kafka` 공식 이미지로 교체. bitnami 전용 환경변수
(`KAFKA_ENABLE_KRAFT`, `ALLOW_PLAINTEXT_LISTENER` 등 `KAFKA_CFG_*` 접두사
네이밍)를 제거하고, 공식 이미지의 네이밍(`KAFKA_PROCESS_ROLES`,
`KAFKA_NODE_ID` 등)으로 StatefulSet 매니페스트를 다시 작성.

**교훈**: 서드파티(비공식) 컨테이너 이미지는 배포 정책이 회사 사정에 따라
예고 없이 바뀔 수 있으므로, 인프라 핵심 컴포넌트는 가능하면 공식 이미지를
우선 채택하는 게 장기적으로 안전하다.

### 2. Kafka 브로커 OOMKilled (메모리 512Mi 부족)

**증상**: Kafka Pod가 정상 기동 후 약 15분 뒤 재시작됨.
`kubectl describe pod` 결과:
```
Last State: Terminated
Reason: OOMKilled
Exit Code: 137
```

**진단 과정**: `kubectl describe pod`로 종료 사유 확인 → `Reason: OOMKilled`,
`Exit Code: 137`(SIGKILL, 메모리 초과로 커널이 강제 종료했다는 신호)로
메모리 부족임을 특정.

**원인**: 메모리 `limit: 512Mi`가 JVM 기반 Kafka(KRaft 브로커+컨트롤러
겸용, 단일 노드라 두 역할을 한 프로세스가 겸함)의 힙+메타스페이스+
JVM 오버헤드를 감당하기엔 부족했음.

**해결**: `requests`/`limits`를 각각 `768Mi`/`1.5Gi`로 증설. 증설 직후
바로 정상 판단하지 않고 **20분 이상 관찰**해 OOMKilled 재발이 없음을
실측 확인.

**교훈**: 리소스 부족으로 인한 재시작은 기동 직후엔 안 보이다가 일정
시간 뒤에 터지는 경우가 있으므로("정상 기동 후 15분"), limit 증설 후에도
곧바로 "해결됨"이라 판단하지 말고 최소 관찰 시간을 두고 재발 여부를
확인해야 한다.

### 3. `__consumer_offsets` 내부 토픽 replication factor 불일치

**증상**: 컨슈머 그룹 기반 조회(`--from-beginning`)는 실패하는데,
파티션을 직접 지정한 조회(`--partition --offset`)는 성공하는 비대칭
현상 발생.

**진단 과정**: `kafka-topics.sh --describe --topic __consumer_offsets` 실행 →
```
Topic 'Optional[__consumer_offsets]' does not exist as expected
```
내부 토픽 자체가 생성되지 않았음을 확인.

**원인**: 내부 토픽 `__consumer_offsets`의 기본 replication factor가
3인데, 브로커가 1대뿐이라 auto-create가 조건을 충족하지 못해 실패.

**해결**: `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` 환경변수를 브로커
설정에 추가.

**교훈**: 단일 브로커 로컬/개발 환경에서는 Kafka 내부 토픽들의 기본
replication factor(보통 3)를 명시적으로 낮춰줘야 하며, 이 설정을
누락하면 컨슈머 그룹 오프셋 커밋처럼 내부 토픽에 의존하는 기능만
조용히 실패해 원인 추적이 까다롭다.

### 4. 로컬 개발 환경에서 K8s 내부 Kafka 접근 시 advertised.listeners DNS 해석 실패

**증상**: 로컬 PC(IntelliJ)에서 Spring Boot 앱 실행 시
```
Couldn't resolve server kafka-0.kafka-headless:9092 ... DNS resolution failed
```

**진단 과정**: `kubectl port-forward svc/kafka-headless 9092:9092`로 최초
연결까지는 되는데 이후 재접속에서 계속 실패하는 패턴을 확인 → 브로커가
클라이언트에게 재접속용으로 돌려주는 `advertised.listeners` 값 자체가
클러스터 내부 DNS라는 점을 의심.

**원인**: `advertised.listeners`가 클러스터 내부 DNS
(`kafka-0.kafka-headless`)만 가리켜서, port-forward로 최초 연결은 되어도
브로커가 재접속 주소로 그 내부 DNS를 다시 알려주는 순간 로컬 PC에서는
해석 불가.

**해결**: `EXTERNAL` 리스너(포트 9094)를 브로커에 추가로 열어, 클러스터
내부 Pod용(`PLAINTEXT`, `kafka-0.kafka-headless:9092`)과 로컬 개발용
(`EXTERNAL`, `localhost:9094`)을 리스너 단위로 분리.
`kubectl port-forward svc/kafka-headless 9094:9094`로 로컬 접근 경로 확보.
`application.yaml`의 `spring.kafka.bootstrap-servers`도 로컬 실행 시엔
`localhost:9094`를 사용하도록 설정.

**교훈**: Kafka는 최초 연결 성공 여부만으로는 안심할 수 없다 —
`advertised.listeners`가 실제로 클라이언트가 도달 가능한 주소인지가
핵심이며, 클러스터 내부/외부 클라이언트가 공존하는 환경에서는 리스너를
용도별로 분리하는 것이 정석이다.

### 5. GitHub Personal Access Token 만료로 인한 401 Bad Credentials

**증상**: `GithubApiClient.searchTrending()` 호출 시
```
401 Unauthorized - "Bad credentials"
```

**진단 과정**: 요청 헤더의 `Authorization` 값이 실제로 전달되는지부터
확인(전달은 정상) → GitHub 웹의 Settings > Developer settings >
Personal access tokens에서 해당 토큰 상태를 직접 조회해 "Expired on ..."
문구 확인.

**원인**: `application.yaml`의 `${GITHUB_TOKEN}` 참조값이 실제로는 과거에
발급한 개인 토큰이었고, 해당 토큰이 만료됨.

**해결**: GitHub Fine-grained Personal Access Token을 최소 권한
(Public Repositories, read-only)으로 새로 발급. IntelliJ Run Configuration의
Environment Variables에 `GITHUB_TOKEN`으로 등록(`application.yaml`에 직접
값을 기재하지 않고, git 커밋 대상에서도 제외).

**교훈**: 인증 실패(401)는 코드 버그가 아니라 자격 증명 자체의 문제일 수
있으므로, 헤더 전달 여부를 먼저 확인한 뒤에는 발급처(GitHub) 화면에서
토큰 상태를 직접 확인하는 것이 코드를 파고드는 것보다 빠른 경우가 많다.

### 6. Kafka 컨슈머 커밋 로직의 finally 블록 오류

**증상**: 실측 재현은 하지 않았으나, 코드 리뷰 중 `CollectConsumer`의
`ack.acknowledge()`가 `finally` 블록에 있어 `DataIntegrityViolationException`이
아닌 다른 예외(DB 연결 실패 등)에서도 무조건 커밋되는 구조적 결함을 발견.

**진단 과정**: `try { ... } catch (DataIntegrityViolationException e) { ... }
finally { ack.acknowledge(); }` 구조를 코드 리뷰로 검토 → `finally`는
예외 종류와 무관하게 항상 실행된다는 점에서, "성공"과 "재시도가 필요한
실패"를 구분하지 못하고 둘 다 커밋해버리는 경로를 확인.

**원인**: `finally`는 어떤 예외가 발생하든 무조건 실행되므로, DB unique
제약 위반(정상적인 멱등 스킵)과 DB 연결 실패 같은 진짜 재시도가 필요한
예외를 구분하지 않고 똑같이 커밋해버림. 커밋되면 Kafka는 "처리 완료"로
인식해 재전달하지 않으므로, 진짜 실패 케이스에서 메시지가 조용히
유실된다.

**해결**: `finally` 제거. `ack.acknowledge()`를 성공 경로(`save()` 직후)와
`DataIntegrityViolationException` catch 경로 두 곳에만 명시적으로 배치.
그 외 예외는 catch하지 않고 그대로 던져서, 커밋 없이 Kafka 재전달을
유도하도록 변경.

```diff
             githubRepositoryJpaRepository.save(repository);
             log.info("레포 저장 완료: fullName={}", message.fullName());
+            ack.acknowledge();
         } catch (DataIntegrityViolationException e) {
             log.info("DB 레벨 중복 감지, 스킵: fullName={}", message.fullName());
-        } finally {
-            ack.acknowledge();
+            ack.acknowledge();
         }
+        // 그 외 예외(DB 연결 실패 등)는 여기서 잡지 않고 그대로 던진다.
+        // ack.acknowledge()를 호출하지 않아야 커밋이 안 되고, Kafka가 재전달한다.
```

**교훈**: `try-finally`로 리소스 정리를 하듯 커밋을 처리하면, "성공"과
"의도적으로 성공 취급하는 특정 실패"와 "진짜 재시도가 필요한 실패"라는
세 가지 경로를 하나로 뭉개버리게 된다. 커밋(ack)처럼 결과에 따라 동작이
달라져야 하는 로직은 finally가 아니라 각 경로에서 명시적으로 처리해야
한다.

## 로컬 실행 중 Kafka 리밸런싱 폭주 + DB "relation does not exist" 일시 오류

Ollama 임베딩 연동 실측 검증을 위해 로컬(IntelliJ) 실행 + `kubectl
port-forward`로 kind의 postgres/kafka에 붙여 장시간(약 30분 이상) 돌리던
중 발생. 재현을 의도적으로 시도한 것은 아니고, 실제 파이프라인 검증
도중 우연히 마주친 문제를 그 자리에서 진단한 기록이다.

**증상**: 22:46:04부터 최소 23:12까지(25분 이상) `collect-group`,
`embed-group`을 포함한 모든 Kafka consumer group이 반복적으로 coordinator를
잃고 재가입을 시도:
```
Group coordinator localhost:9094 ... is unavailable or invalid due to cause: error response NOT_COORDINATOR
Request joining group due to: group is already rebalancing
```
in-flight 요청이 최대 **330777ms(5.5분)**, 이후 **1057191ms(17.6분)** 동안
붙잡혀 있다가 끊김. 같은 구간에 `HikariPool - Failed to validate
connection ... This connection has been closed`(DB 커넥션 검증 실패),
Ollama 쪽 `ReadTimeoutException`도 반복. 이 구간 중 22:47:10에
`CollectConsumer` DLT 핸들러에서
```
ERROR: relation "github_repository" does not exist
Position: 229
```
가 여러 레포에 대해 연속으로 찍힘.

**진단 과정**: "relation does not exist"만 보면 스키마 유실을 의심할
상황이라, 먼저 postgres Pod 자체를 확인:
```
$ kubectl get pod -l app=postgres
postgres-6d94bb5bf5-qzt4q   Running   restartCount=0   startTime=2026-08-14T09:09:32Z
Events: <none>
```
사고 구간 전후로 재시작 이력 없음(같은 Pod, 같은 시작 시각) — postgres
자체는 죽거나 재생성된 적이 없음을 확정. 이어서 로컬 port-forward
프로세스도 확인:
```
$ netstat -ano | grep :5432
TCP 127.0.0.1:5432  LISTENING  15112
$ tasklist /FI "PID eq 15112"
kubectl.exe  15112
```
5432를 점유한 프로세스가 처음 시작한 것과 동일한 kubectl port-forward
하나뿐임을 확인(포트 충돌·로컬 docker-compose postgres 개입 아님).

**원인**: postgres Pod 재시작이 아니라는 것은 확정했으나, "relation does
not exist"가 정확히 왜 발생했는지는 로그만으로 100% 확정하지 못했다.
Kafka(9094)·DB(5432)·Ollama(11434) 세 개 커넥션이 같은 시간대에 동시에
불안정해진 정황(반복적 coordinator 유실, HikariCP 커넥션 검증 실패,
Ollama 타임아웃)으로 볼 때, `kubectl port-forward` 터널 또는 로컬
PC/Docker Desktop 자체가 이 구간 동안 불안정했을 가능성이 가장 유력하다.
터널이 흔들리는 동안 죽은 커넥션 일부가 HikariCP 검증을 통과해 재사용되며
깨진 TCP 스트림을 pgJDBC가 잘못 해석해 이런 에러를 냈을 것으로 추정 —
다만 port-forward 프로세스 자체의 끊김/재연결 로그가 남지 않아 이 부분은
"확정된 원인"이 아니라 "가장 유력한 정황 증거"로 남긴다.

**해결**: 별도 조치 없음 — `@RetryableTopic`(지수 백오프 3회) + DLT
설계가 그대로 작동해 자동 복구됨. 사고 구간 이후 같은 레포들이 다음
스케줄러 사이클에서 정상 재수집·재임베딩됨을 실측 확인(예:
`sindresorhus/awesome`이 DLT 도달 후 다음 사이클에서
`레포 갱신 완료: stars=495620` 로그로 정상 처리).

**영향 범위(실측)**: 이 사고로 데이터가 유실되지 않았음을 최종 상태
조회로 확인. `repo_embeddings` 10건 중 3건(`openclaw/openclaw`,
`practical-tutorials/project-based-learning`, `tensorflow/tensorflow`)은
과거 성공한 벡터는 남아있지만 이 구간 재시도 실패로 `process_status`가
`FAILED`로 확정됨(RAG 추천 쿼리는 `status=EMBEDDED`만 필터링하므로 다음
성공 사이클 전까지 추천 대상에서 제외되는 정상 동작).

**교훈**: `kubectl port-forward`는 배포 환경(k3s, Pod 내부 통신)에 없는
로컬 전용 경로라 이 구간이 유일한 단일 장애점(SPOF)이 된다. "relation
does not exist" 같이 스키마 문제처럼 보이는 에러가 나와도, 먼저 대상
Pod의 재시작 이력(`restartCount`, `startTime`, `Events`)부터 확정하고
그 다음 네트워크 경로를 의심하는 순서가 안전하다 — 겉보기 에러 메시지와
실제 계층(스키마 vs 네트워크)이 다를 수 있다. 이 문제는 구조적으로
배포 환경(k3s)에서는 재현되지 않는다(Pod 내부 통신에는 로컬 전용
port-forward 경로가 끼지 않음).

## EmbedConsumer 구조적 한계 — poll 스레드 동기 처리로 인한 리밸런싱

앞의 사고와 별개로, 같은 로컬 환경(IntelliJ + `kubectl port-forward`)에서
DB/Redis를 완전히 비운 클린 상태로 30건 재수집을 처음부터 관찰하던 중
`embed-group`이 반복적으로 리밸런싱되는 것을 실측으로 재현·확정했다.

**증상**: 클린 재시작 직후 `embed-group`이 몇 분 간격으로 계속 멤버
이탈→재가입을 반복(`kubectl logs kafka-0`):
```
Member ...embed-group has failed, removing it from the group
Group embed-group is dead, skipping rebalance stage
Stabilized group embed-group generation N ...
```
generation 번호가 짧은 시간 안에 9 → 10 → 13 → 14로 계속 올라감. 이 동안
DB의 `process_status`는 진전이 없거나(COLLECTED 그대로) 처리 중이던
항목이 통째로 무효화되어 재시도되는 패턴이 반복됨.

**1차 진단**: `codescope.ollama-semaphore.permits`가 기본값 2인데
`EmbedConsumer`의 `@KafkaListener(concurrency = "3")`과 맞지 않아, poll
스레드가 세마포어 대기 + Ollama 순차 호출로 오래 묶이는 것으로 추정.
`permits`를 2→4로 올려 재시도.

**2차 진단(근본 원인 확정)**: 코드 확인 결과 `EmbedConsumer.consume()`
(리스너 메서드, 즉 poll 스레드에서 직접 실행)이
`githubApiClient.fetchReadme()`, `embeddingService.embed()`를 전부 동기로
호출하고 있음(`OllamaEmbeddingService.embed()`는 README를 500자 청크로
쪼개 청크마다 Ollama HTTP 호출을 순차 반복). 별도 스레드/큐로 위임하는
코드가 전혀 없음 — **poll 스레드 안에서 배압 제어(세마포어 대기)를 하는
구조라, 배압이 곧 Kafka 생존 신호(heartbeat)까지 막아버리는 설계
결함**임을 확인.

**임시 완화책 적용**: `spring.kafka.consumer.properties.max.poll.interval.ms`를
기본값 300000(5분)에서 900000(15분)으로 늘려 poll 스레드가 오래 묶여도
Kafka가 죽은 컨슈머로 판단하지 않도록 함. **증상 완화일 뿐 근본 해결이
아님을 명시** — README가 극단적으로 긴 레포("awesome list"류)는 청크 수가
수십~수백 개에 달해 총 처리 시간이 15분도 넘길 수 있기 때문.

**실측 결과 — 완화책의 한계가 그대로 재현됨**:
- 백로그(구 실패분) 16건은 permits=4 적용 후 정상적으로 전부 소진되어
  EMBEDDED로 전환됨(리밸런싱 없이 진행)
- 반면 README가 매우 긴 14건(`awesome-selfhosted/awesome-selfhosted`,
  `freeCodeCamp/freeCodeCamp`, `vinta/awesome-python`,
  `DigitalPlatDev/FreeDomain`, `TheAlgorithms/Python`,
  `microsoft/vscode`, `react/react` 등)은 백로그가 다 빠진 뒤에도
  20분 넘게 단 1건도 완료되지 못함(단, Ollama `/api/ps`의
  `expires_at` 기준 역산 결과 요청 자체는 계속 들어가고 있어 "완전히
  멈춘 것"은 아니고 "매우 느리게 순차 처리 중"이었음을 확인)
- 처리 시작(~19:58 KST) 후 약 15분 지점(20:05:13 KST)에
  `consumer-embed-group-17-...`가 heartbeat 만료로 그룹에서 강제 제거되며
  리밸런싱 재발을 확인 — **max-poll-interval을 5분→15분으로 늘려도
  "가장 큰 레포"가 그 값을 넘는 순간 동일 증상이 재발함**이 실측으로
  확정됨

**최종 스냅샷 (2026-08-15 20:15:35 KST, 관찰 종료 시점)**:

| process_status | 건수 |
|---|---|
| EMBEDDED | 16 |
| COLLECTED (대형 README, 미해결) | 14 |
| FAILED | 0 |

대형 README 14건은 강제로 FAILED 처리하지 않고 그대로 둠 —
`@RetryableTopic(attempts = "3")`으로 3회 소진 후 자연스럽게 DLT/FAILED로
수렴하는 구조라 무한 재시도 루프 위험이 없음을 코드로 확인했고(Kafka
파이프라인 트러블슈팅 6번 이슈에서 커밋 로직 자체는 이미 정상 처리되도록
고쳐진 상태), 데이터
유실 위험도 없어 컨슈머를 별도로 멈추지 않았다.

**적용된 설정값(오늘 실측 기준으로 그대로 유지)**:
```yaml
# application.yaml
codescope:
  ollama-semaphore:
    permits: 4   # 기본값 2 → 4

spring:
  kafka:
    consumer:
      properties:
        max.poll.interval.ms: 900000   # 기본값 300000(5분) → 900000(15분)
```

**근본 해결 (다음 세션 과제, 오늘은 미적용)**: 임베딩 처리(README
다운로드 + 청킹 + Ollama 순차 호출)를 poll 스레드에서 분리해 별도
스레드/큐로 비동기 위임하는 구조로 리팩토링. poll 스레드는 메시지를
받는 즉시 큐에 넘기고 곧바로 다음 poll로 돌아가게 해, 배압(세마포어
대기)이 Kafka heartbeat와 완전히 분리되도록 한다. 리팩토링 전까지는
`max.poll.interval.ms` 여유값(15분)이 "이 시점 기준 관측된 가장 느린
레포"를 커버하는 임시 안전판 역할만 한다는 점을 인지하고 있어야 한다.

## EmbedConsumer poll 스레드 분리 + RestClient 타임아웃 실측 검증 (근본 리팩토링)

위 항목의 "근본 해결" 과제를 실제로 구현하고 같은 날 실측 검증까지
완료한 기록.

### 설계

`EmbedConsumer.consume()`(poll 스레드)은 이제 `EmbedMessage`를 받으면
`embedWorkerExecutor`(bounded `ThreadPoolExecutor`, `EmbedWorkerConfig`)에
작업만 제출하고 즉시 반환한다. 실제 README 다운로드 + 청킹 + Ollama
호출 + DB 저장은 별도 워커 스레드가 전담한다.

**기존 `@RetryableTopic`(3회 재시도+DLT)과의 관계**: 워커 스레드에서
발생하는 실패는 poll 스레드로 예외를 전파할 방법이 없어(리스너 메서드가
이미 정상 반환한 뒤이므로) 그대로 재사용할 수 없다. 대신 동일한 정책
(3회 재시도, 지수 백오프 1000ms×2.0, 4xx 제외, 최종 실패 시
`markFailed()`)을 워커 스레드 안에서 인메모리로 재현했다. `@RetryableTopic`
자체는 남겨뒀지만 이제 실제로 담당하는 범위는 "작업 큐가 가득 차서
`RejectedExecutionException`이 발생하는 경우"(백프레셔) 하나뿐이다.

**오프셋 커밋 시점**: `ack.acknowledge()`는 워커가 성공 또는 최종 실패를
확정한 뒤에만 호출한다(`Acknowledgment`는 스레드 세이프해서 워커
스레드에서 직접 호출 가능). auto-commit은 계속 꺼진 채로 유지.

**트레이드오프(의도적으로 감수)**: 작업 큐가 인메모리라 앱 재시작 시
이미 큐에 들어갔지만 아직 워커가 다 처리하지 못한 작업은 유실될 수
있다. 하지만 이 시점엔 해당 메시지가 아직 커밋되지 않은 상태이므로,
재시작 후 Kafka가 커밋된 오프셋 이후부터 다시 전달해 재처리된다
(at-least-once 유지됨).

### 1차 실측 — 리밸런싱은 사라졌으나 새 증상 발견

리팩토링 적용 후 재기동해 COLLECTED 14건(대형 README)을 재처리시킨 결과:
- **리밸런싱 완전히 재현 안 됨**: 메시지 하나가 20분 넘게 "처리 중"(lag
  미해소) 상태로 붙잡혀 있어도 `embed-group has failed`/`is dead` 로그가
  한 번도 안 찍힘. 리팩토링 이전이었으면 15분 근처에서 반드시 재현되던
  것과 대비.
- 그런데 Ollama `/api/ps`의 `expires_at`이 갱신을 멈춤(호출 자체가
  끊김). `GithubApiClient`, `OllamaEmbeddingService`가 사용하는
  `RestClient` 두 곳 모두 connect/read 타임아웃이 전혀 설정되어 있지
  않음을 코드로 확인 — 워커 스레드가 GitHub API 또는 Ollama 호출에서
  무한 대기했을 가능성으로 진단.

### 타임아웃 적용

`RestClientConfig`(GitHub), `OllamaRestClientConfig`(Ollama) 각각에
`SimpleClientHttpRequestFactory`로 명시적 타임아웃 추가:

| 대상 | connect | read | 근거 |
|---|---|---|---|
| GitHub API | 3초 | 10초 | README 원문(텍스트) 응답 받기엔 충분하되 무한 대기는 확실히 차단 |
| Ollama | 3초 | 20초 | 청크당 실측 처리 시간(2026-08-14, 약 3.5초)의 5배 이상 여유 — 너무 짧으면 정상 처리 중인 요청도 타임아웃으로 오판됨을 경계 |

타임아웃 발생 시 `SimpleClientHttpRequestFactory`는
`ResourceAccessException`(RuntimeException 계열)을 던지는데, 이는
`EmbedConsumer.processWithRetry()`의 일반 `catch (Exception e)` 분기로
정상적으로 잡혀 기존 재시도 정책(3회+백오프)을 그대로 타는 것을 코드로
확인(별도 처리 불필요).

### 2차 실측 — 진짜 원인은 타임아웃이 아니라 청크 수 자체였음

타임아웃 적용 후 재기동해 40분 가까이 관찰했으나 대형 README 14건은
여전히 단 1건도 완료되지 않음. 리밸런싱도 40분 내내 0건으로 안정적.

**원인 재진단**: `awesome-selfhosted/awesome-selfhosted`의 실제 README를
직접 받아본 결과 **327,708 bytes**. `OllamaEmbeddingService`가 500자
단위로 청킹하므로 약 **655개 청크**가 나오고, 청크당 Ollama 호출이
실측 약 3.5초라 이 레포 하나만 순차 처리하는 데 **655 × 3.5초 ≈ 38분**이
소요된다. 개별 Ollama 호출은 20초 타임아웃 안에 매번 정상 응답하므로
타임아웃도 안 걸리고, poll 스레드도 막히지 않으므로 리밸런싱도 안 나는
것이 전부 "정상 동작"이었다 — 다만 청크 단위 순차 처리 구조가 초대형
문서 앞에서 압도적으로 느릴 뿐이었다.

### 최종 결론

- **오늘 리팩토링의 목표(poll 스레드 처리 시간과 Kafka 컨슈머 생존을
  분리)는 실측으로 완전히 검증됨**: 리팩토링 전에는 15분 근처에서
  반드시 재현되던 리밸런싱이, 리팩토링 후에는 메시지가 40분 가까이
  묶여 있어도 전혀 재현되지 않음.
- RestClient 타임아웃 부재는 별개의 실제 결함이었고(발견 즉시 수정),
  타임아웃 자체는 정상 동작하나 이번 스톨의 직접 원인은 아니었음
  (원인은 청크 수 × 청크당 지연의 누적).
- **남겨진 과제(다음 세션)**: 청크 500자/순차 처리 구조는 수백 KB급
  README 앞에서 수십 분이 걸린다. README 길이 상한(예: 앞부분 N자만
  임베딩), 청크 병렬 처리, 또는 청크 수 자체에 상한을 두는 방안을
  검토해야 함. 오늘은 시간 관계상 적용하지 않고 관찰만 종료함.

**최종 스냅샷 (2026-08-15 21:29:20 KST)**:

| process_status | 건수 |
|---|---|
| EMBEDDED | 10 |
| FAILED | 6 |
| COLLECTED (초대형 README, 처리 진행 중) | 14 |

**세션 종료 처리**: IntelliJ를 계속 켜두는 것은 현실적이지 않아 앱을
정상 종료함. 종료 시점에 안전한지 코드로 재확인:
`enable-auto-commit: false` + `AckMode.MANUAL`이고, `EmbedConsumer.consume()`
(poll 스레드)은 작업을 워커 큐에 넘기기만 할 뿐 `ack.acknowledge()`를
직접 호출하지 않는다 — ack는 워커 스레드가 성공/최종실패를 확정한
시점에만 호출된다. 따라서 종료 시점에 워커가 처리 중이었거나 큐에
대기 중이던 메시지는 한 번도 커밋되지 않은 상태이므로 Kafka 브로커에
그대로 남고, **다음 세션에 IntelliJ를 다시 켜면 마지막 커밋 오프셋부터
자동으로 재전달되어 이어서 처리된다**(데이터 유실 없음, at-least-once
유지). 남은 COLLECTED 14건(초대형 README)은 별도 조치 없이 다음 실행
시 그대로 재개됨.

## RAG 레포 추천(RepoRecommendService) 구현 및 llama3.2:3b 반복 생성 열화 실측

임베딩 파이프라인에 이어 pgvector 유사도 검색 + LLM 생성을 묶은
RAG 추천(`RepoRecommendService`)과 트렌드 분석(`TrendAnalysisService`)을
구현하고, 실제 로컬 인프라(Postgres/Ollama)로 통합 테스트까지 실행해
검증한 기록.

### 구현 범위

- `LlmClient`/`OllamaLlmClient`: Ollama(`llama3.2:3b`)로 텍스트 생성.
  임베딩(`OllamaEmbeddingService`)과 동일한 Ollama 프로세스를 쓰므로
  세마포어(`ollamaSemaphore`)를 공유(별도 Bean으로 나누지 않음 — 근거는
  `OllamaSemaphoreConfig` 클래스 주석 참고)
- `EmbeddingService.embedQuery()` 신규 추가: 기존 `embed()`는 인덱싱용
  `search_document: ` prefix가 하드코딩돼 있어 검색 질의에 그대로 쓰면
  안 됨(nomic-embed-text는 문서/질의를 다른 벡터 공간으로 학습) — 질의
  전용 `search_query: ` prefix 메서드를 분리
- `RepoEmbeddingJpaRepository.findNearestEmbeddedByEmbedding()` 신규
  추가: 기존 `findNearestByEmbedding()`(pgvector 검증 테스트가 사용 중이라
  유지)은 `process_status` 필터가 없어, 과거 EMBEDDED였다가 이후 실패로
  FAILED가 된 레포(실측으로 존재 확인됨)도 섞여
  나올 수 있음 — `github_repository`와 JOIN해 `status='EMBEDDED'`만
  걸러내는 별도 쿼리로 분리
- `IssueRecommendService`는 스코프에서 제외(TODO로 `IssueJpaRepository`에
  기록) — `issues` 테이블 0건, 수집 파이프라인 자체가 없어 실제 데이터로
  검증할 방법이 없었음. Issue 엔티티 필드(body/url/labels)도 없어 근본
  해결에는 별도 수집 파이프라인 신규 구현이 선행돼야 함

### 실측 1 — 통합 테스트로 환각 방지 검증

`RepoRecommendServiceIntegrationTest`(`@SpringBootTest`, 실제 Ollama +
실제 Postgres/pgvector 사용, Ollama reachability 체크로 CI 자동 스킵)로
검증: 스택과 밀접한 레포 1개 + 무관한 레포 1개를 실제 Ollama 임베딩으로
저장한 뒤 `recommend("Java, Spring Boot, Kafka")` 호출 시, 응답 후보
목록에 두 레포가 모두 포함되고 LLM 응답 텍스트에 실제 후보 레포 이름이
등장하는지(목록 밖 이름을 지어내지 않는지) 확인.

### 실측 2 — RestClient 생성 타임아웃 재조정

첫 통합 테스트 시도에서 `Read timed out` 발생. `keep_alive=0`으로
모델을 강제 언로드한 뒤 콜드 스타트를 직접 실측한 결과 **77.8초**(모델
로드 28초 포함)가 걸림을 확인 — 처음 잡은 60초 타임아웃은 이 콜드
스타트를 전혀 커버하지 못했음. 실측값의 약 2배 여유를 둔 **150초**로
`llm.ollama.generation.read-timeout-ms`를 재조정.

### 실측 3 — llama3.2:3b 반복 생성 열화(repetition degeneration)

타임아웃 재조정 후에도 통합 테스트가 계속
`Expecting "사용자 기술" to contain "test-recommend/spring-kafka-toolkit"`로
실패. 원인 진단을 위해 동일 프롬프트를 curl로 직접 호출해본 결과:

- 모델이 같은 추천 문장("test-recommend/spring-kafka-toolkit ... 이유")을
  **3번 그대로 반복**하다가 context 길이(4096 토큰)를 다 소진해, 응답이
  "사용자 기술"이라는 단어 중간에서 강제로 잘림(`"done": false`)
- 3B급 소형 로컬 모델에서 흔히 나타나는 반복 생성 열화 현상. 반복이
  스스로 멈추지 못하고 토큰 예산을 전부 써버려 정작 필요한 답변 뒷부분이
  잘려나가는 것이 핵심 문제

**해결**: `/api/generate` 요청에 `options.repeat_penalty: 1.3`(Ollama
기본값 1.1보다 강화) 추가. 동일 프롬프트로 재검증한 결과 반복 없이
`"done": true, "done_reason": "stop"`로 정상 완결, 후보 레포 2개 모두에
대해 서로 다른 이유로 설명하는 응답을 받음. 이후 통합 테스트도
`BUILD SUCCESSFUL`로 통과(테스트 자체 소요 시간 98.259초 — 임베딩 2회 +
pgvector 검색 + LLM 생성 전체 포함).

### 실측 4 — 다국어 문자 섞임 정도 확인 및 repeat_penalty 재조정 실험

`repeat_penalty` 적용 후에도 한국어 응답에 다른 스크립트(일본어/태국어/
베트남어/중국어) 단어가 섞이는 것을 발견해, "한두 글자 수준인지 문장이
못 알아볼 정도로 깨지는지" 실제 원문으로 정도를 확인했다.

**응답 원문(1.3, 발췌)**: "이 **レ**포에서 개발할 수 있는 다양한**機能**
에는...", "도메인을**ครอบ giữ**하는 데에 큰 기여가**できる** 것이고...",
"사용자**技术** 스택과 잘 호환되며..." — 단어 5곳이 완전히 다른 언어
토큰으로 대체됨. 문장 흐름은 문맥으로 대략 유추 가능하나 "한두 글자"
수준을 명백히 넘는 정도로 판단.

**repeat_penalty가 다국어 섞임의 원인인지 확인**: 같은 프롬프트로
1.1(옵션 없음, Ollama 기본값) / 1.15 / 1.3 세 값을 비교(설정당 1회씩):

| repeat_penalty | 결과 | 다른 스크립트 삽입 | 반복 열화(응답 중단) | 비고 |
|---|---|---|---|---|
| 1.1(기본) | 완결 | 1곳(힌디어) | 있음(거의 동일한 항목 3개 반복) | 30초 |
| 1.15 | 완결 | 1곳(일본어+영단어) | 없음 | 14초, 가장 간결 |
| 1.3 | 완결 | 5곳(다국어 혼합) | 없음 | 142초 |

1회차 결과만 보면 1.15가 반복 열화도 없고 다국어 삽입도 적어 더 나아
보여 코드를 1.3→1.15로 낮췄으나, **재현 검증을 한 번 더 돌리자 1.15에서도
반복 열화가 재현됨**(`RepoRecommendServiceIntegrationTest` 재실행 시
응답이 `"저는 다음 3개의 레포가 사용자 기술"`에서 그대로 잘림, `done: false`
와 동일한 실패 패턴). LLM 생성이 확률적 샘플링이라 설정당 표본
1~2개로는 결론이 쉽게 뒤집힌다는 것 자체가 실측으로 확인됨.

**최종 결정**: 지금까지 실측 성공/실패 누적 — 1.3은 2회 시도(수동 curl
1회 + 통합 테스트 1회) 모두 반복 열화 없이 완결, 1.15는 2회 중 1회
반복 열화로 실패. "반복 열화로 응답 전체가 망가지는 것"이 "가끔 섞이는
외국어 단어"보다 명백히 더 나쁜 실패 모드이므로, 표본이 적어도 더 안전한
1.3을 유지하기로 결정하고 코드를 되돌림.

**결론 및 남은 한계**: `repeat_penalty` 튜닝만으로는 다국어 삽입과
반복 열화 두 문제를 동시에 완전히 해결하지 못한다 — 둘 다 이 3B급
로컬 모델의 근본적인 한계로 보임. 오늘 검증 목표(환각 방지: 응답에
실제 후보 레포 이름이 포함되는지)는 통과했지만, 사용자에게 노출되는
프로덕션 응답 품질 관점에서는 근본 해결이 아니다. **다음 세션 과제**:
(1) 상위 모델 교체 검토, (2) `num_predict` 명시적 상한 + 응답의
`done` 필드가 `false`면 반복 열화로 판단해 자동 재시도하는 방어 로직
추가, (3) 다국어 삽입만 별도로 줄이는 프롬프트 엔지니어링(예: 출력
언어를 더 강하게 지시) 실험.

### 실측 5 — 프롬프트에 "반드시 한국어로만 답하라" 명시 추가(효과 미미)

위 (3)번 과제를 바로 시도해봄. `RepoRecommendService`/`TrendAnalysisService`
프롬프트 확인 결과, 지금까지 "프롬프트 자체가 한국어니 응답도 당연히
한국어일 것"이라 암묵적으로 기대했을 뿐 명시적 제약이 전혀 없었음을
확인. "목록 안에서만" 제약과 동일하게 머리말+맺음말 두 곳에
"반드시 한국어로만 답하세요. 다른 언어 단어를 섞지 마세요"를 추가.

**실측(1회, 같은 프롬프트 구조에 제약만 추가)**: 여전히 4곳 정도
다국어/깨짐 발생 — 아랍/우르두 문자 삽입("`1~3개 را 다음과`"), 영단어
혼입("`이러한 technologies의`"), 한국어 단어 자체가 깨짐("스프링"이
"`스파RING`"으로), 한자 삽입("`기술 스택과直接 관련이`"). 추가로 이번엔
"3. test-recommend/spring-kafka-toolkit (again)"처럼 **모델이 스스로
반복임을 자각하면서도 또 반복하는** 새로운 패턴까지 관찰됨.

**결론**: 예상대로(사용자가 사전에 지적한 트레이드오프 그대로)
프롬프트 강화만으로는 3B 모델의 다국어 생성 열화를 뚜렷하게 개선하지
못함. 다만 코드 변경 비용이 거의 없고 해가 되지 않아 그대로 유지 —
"시도조차 안 한 상태"는 벗어났고, 다른 대책(모델 교체 등)과 병행 시
약간의 보탬은 될 수 있다는 판단.

### 실측 중 발견한 부수 이슈 — port-forward 불안정성

이번 검증 과정(약 1시간, 여러 차례 `./gradlew test` 재시도) 동안
`kubectl port-forward`로 연결한 postgres/redis가 **3회** 별다른 예고
없이 끊김을 반복 관찰. 앞의 트러블슈팅에서 이미 "로컬 전용 SPOF"로
지목된 문제가 이번에도 재현됨 — 매번 재연결로 대응했으나, 장시간 검증
작업 전에는 port-forward 생존을 주기적으로 확인하는 습관이 필요함을
재확인.

## nomic-embed-text 한국어 검색 한계 발견

생성(LLM) 쪽 다국어 섞임 문제를 조사하던 중, 사용자 질문("임베딩 쪽도
깨지나?")을 계기로 임베딩 모델의 언어 처리 자체를 별도로 실측 검증함.

**결론부터**: 임베딩은 텍스트를 생성하는 게 아니라 숫자 벡터로
변환하는 것뿐이라 "깨짐"(다른 스크립트 혼입 등) 자체가 발생할 수 있는
구조가 아니다. 대신 실제 문제는 **의미 이해 품질이 언어에 따라 크게
다르다**는 것이었다.

**실측(코사인 유사도, 질의=`search_query:`, 문서=`search_document:`
prefix — 실제 코드와 동일 조건)**:

- 완전한 한국어 자연어 질의(예: "Kafka 관련 문서 찾아줘")로 코사인
  유사도 검색 시, 관련 문서(0.5148)와 무관 문서(0.5156) 구분이 거의
  안 됨(사실상 랜덤 수준)
- 영어 질의는 명확히 구분됨(관련 0.7532 vs 무관 0.4367)
- 원인 추정: nomic-embed-text가 영어 중심 학습 + README 코퍼스도
  대부분 영어라서 한국어 의미 이해가 약함
- 현재 `RepoRecommendService`는 `stack` 파라미터가 쉼표 구분 영어
  기술명 나열(예: `"Java,Spring Boot,Kafka"`)이라 이 한계에 걸리지
  않음(실측: 관련 0.8328 vs 무관 0.4918, 명확히 구분됨) — **단, 이건
  의도된 설계가 아니라 우연히 API 명세가 영어 나열형이라 회피된
  것임을 명시한다.**

**향후 재검토 필요 시점**: "자유 서술형 한국어 검색" 기능을 추가할
경우 이 한계가 바로 드러난다. 그때 재검토할 옵션: 다국어 임베딩
모델(예: multilingual-e5)로 교체, 또는 입력 전처리(한국어→영어 번역
단계 추가).

**오늘은 코드 변경 없이 기록만 남김** — 현재 사용 패턴에서는 실질적
영향이 없다고 판단.
