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
