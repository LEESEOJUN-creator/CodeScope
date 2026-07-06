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
