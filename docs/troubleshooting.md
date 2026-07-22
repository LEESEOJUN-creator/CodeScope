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
