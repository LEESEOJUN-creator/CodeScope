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
발생하므로 페이징 없는 목록 조회에만 적용. 페이징이 필요한 검색 API(Day9)엔
default_batch_fetch_size 적용 예정.

**주의**: 실행 시간(ms) 단위의 성능 비교는 이번 테스트의 duration에 스프링
컨텍스트 로딩/스키마 재생성 시간이 포함되어 있어 신뢰할 수 없음. 실제 응답
시간 개선 수치는 Day32 JMeter 부하테스트에서 별도 실측 예정.

### 관련 테스트

- `GithubRepositoryTopicNPlusOneTest` / `GithubRepositoryTopicFetchJoinTest` — 소규모(레포 5개) 재현/해결
- `GithubRepositoryTopicNPlusOneScaleTest` / `GithubRepositoryTopicFetchJoinScaleTest` — 대량(레포 100개) 재현/해결
