# LLM 호출 트랜잭션 경계 + 임베딩 버스트 처리 (2026-10-13)

로컬 kind(Postgres/Redis/Kafka) + 호스트 앱(`bootRunLoadTest`, stub LLM) 환경에서
처음으로 다중 경로 부하테스트를 돌리면서 드러난 문제 세 가지와 그 수정 결과를 정리한다.

## 1. LLM 대기 중 DB 커넥션 점유 → 풀 고갈

### 증상 (기준선)
k6 `scenarios.js`, mixed, RATE=20, 1분, stub 지연은 생성 2초, 임베딩 200ms.

| 지표 | 값 |
|---|---|
| browse(LLM 무관 조회) p95 | **30.0s** (Hikari connectionTimeout과 정확히 일치) |
| 실패율 | 12.9% |
| Hikari active / pending (최대) | 10 / **211** |
| `Connection is not available` 로그 | 372건 |
| GC 최대 pause / CPU | 18ms / 3% (원인 아님) |

### 원인
- `RepoRecommendService`와 `TrendAnalysisService`에는 클래스 레벨 `@Transactional`이 걸려 있다. 그래서 LLM 응답을 기다리는 수 초 동안에도 트랜잭션, 즉 DB 커넥션을 붙잡고 있었다.
- OSIV(`spring.jpa.open-in-view`)가 기본값 true였다. OSIV가 켜져 있으면 커넥션은 요청이 끝날 때까지 반환되지 않는다.
- 결과적으로 추천 요청 몇 건이 들어와 LLM 세마포어 앞에 줄을 서면, 대기하는 동안에도 커넥션 10개를 모두 차지한다. 그러면 세마포어와 무관한 조회 API까지 30초 타임아웃으로 무너진다. DB를 보호하려던 세마포어 설계가 트랜잭션 범위 때문에 무력화된 셈이다.
- 추가로 발견: 추천 후보 10개를 엔티티(`RepoEmbedding`)로 받은 뒤 `getRepository()`를 지연 로딩하고 있었다. 후보마다 SELECT가 1회씩 더 나가는 N+1 구조였다.

### 수정
- **OSIV 끔:** `spring.jpa.open-in-view: false`. 엔티티를 DTO로 바꾸는 작업은 원래 모든 서비스가 트랜잭션 안에서 끝내고 있었다.
- **추천:** 클래스 레벨 `@Transactional`을 제거했다. pgvector 검색은 `RecommendCandidateView` 프로젝션으로 JOIN 결과의 컬럼을 바로 받는다. 쿼리 1번으로 끝나서 N+1이 사라지고, 커넥션은 이 쿼리 동안에만 쓴다.
- **트렌드 분석:** 클래스 레벨 `@Transactional`을 제거하고 "조회 → LLM(트랜잭션 밖) → 저장" 순서로 나눴다.
  - 저장은 `INSERT ... ON CONFLICT (repo_id) DO UPDATE` upsert 한 문장으로 바꿨다. 예전 "조회 후 save" 방식에서는 동시에 끝난 두 분석이 모두 INSERT를 시도해 UNIQUE 제약을 위반할 수 있었는데, 이 경합이 사라진다.
  - `@Cacheable(sync = true)`를 걸었다. 같은 레포에 캐시 미스가 동시에 몰려도 LLM은 한 번만 호출된다(비용 절감).
- **회귀 테스트:** `LlmCallTransactionBoundaryTest`. MockMvc로 실제 HTTP 경로(OSIV 인터셉터 포함)를 타게 하고, LLM mock이 호출되는 순간 그 스레드에 트랜잭션, EntityManager, JDBC 커넥션이 묶여 있지 않은지 검사한다. 예전처럼 `@Transactional`을 다시 붙이면 테스트가 실패하는 것까지 확인했다.

### 결과 (같은 시나리오, 같은 조건)

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| browse p95 | 30.0s | **9.41ms** |
| 실패율 | 12.9% | **0%** |
| Hikari pending (최대) | 211 | **0** |
| 커넥션 타임아웃 | 372 | **0** |
| 처리 요청 수 (1분) | 958 | 1,554 |

### 남은 과제 → 1-1에서 해결
수정 후에도 recommend와 trend의 p95는 각각 57초, 47.5초였다. 원인은 claudeSemaphore 대기열(최대 171)이다. permits가 4이고 stub 지연이 2초라서 LLM 경로의 처리 상한은 약 초당 2건인데, 유입은 그보다 많아 대기열이 끝없이 쌓였다. 다만 이 포화는 LLM 경로 안에만 갇혀 있었다.

## 1-1. LLM 대기열 무한 증가 → 대기 상한 + 503 (load shedding)

### 수정
- **`LlmAdmissionControl`:** 세마포어를 `acquire()`(무한 대기) 대신 `tryAcquire(acquire-timeout-ms = 5s)`로 얻는다. 상한을 넘기면 `LlmOverloadedException`을 던진다.
- **응답 변환:** `GlobalExceptionHandler`가 이 예외를 **503 + Retry-After: 5**로 바꾼다. 서버 고장(500)이 아니라 "잠시 후 다시"라는 표준 신호다.
- **적용 범위:** ClaudeLlmClient와 StubLlmClient가 같은 입장 제어를 거친다. 거절 횟수는 `codescope_llm_shed_total` 지표로 남기고, Grafana에 거절 패널을 추가했다.
- **k6:** LLM 경로의 503은 의도된 거절이므로 `http_req_failed`에서 빼고, `llm_shed_rate`로 따로 집계한다.

### 결과 (같은 시나리오, 같은 조건)

| 지표 | 1 수정 전 | 1 수정 후 | 1-1 수정 후 |
|---|---|---|---|
| browse p95 | 30.0s | 9.41ms | 8.72ms |
| recommend p95 | 35.5s | 57.3s | **7.1s** |
| trend p95 | 30.0s | 47.5s | **6.76s** |
| 실패율 (서버 오류) | 12.9% | 0% | 0% |
| LLM 거절(503) 비율 | - | - | 41.7% |
| claudeSemaphore 대기 (최대) | - | 171 | 43 |
| dropped_iterations | 834 | 131 | **3** |
| 처리 요청 수 (1분) | 958 | 1,554 | 1,800 |

거절 비율 41.7%는 처리 상한(약 초당 2건)보다 유입이 많다는 사실을 그대로 드러낸 숫자다. 이제 사용자는 1분씩 기다리는 대신 최대 약 7초 안에 결과를 받거나, "5초 후 재시도" 안내를 받는다.
실제 운영에서는 permits를 Claude API의 rate limit에 맞춰 키워 처리 상한 자체를 올린다. 이 비율은 그 조정의 근거로 쓴다.

## 2. 임베딩 큐 버스트 → 영구 FAILED

### 증상
첫 수집 사이클에서 30건 중 3건이 FAILED로 확정됐다.
- 12:09:42: 30건이 한꺼번에 들어와 워커 6 + 큐 20 = 26을 초과했다. 즉시 거절(`AbortPolicy`)됐다.
- 12:09:45~46: 거절된 메시지가 재시도 토픽에서 1초, 2초 백오프 뒤 다시 들어왔지만 큐가 여전히 차 있어 또 거절됐다. 결국 DLT로 가서 FAILED가 됐다.
- 12:10:19: 이때서야 큐가 완전히 비었다(약 37초 소요).

재시도 기회(약 3초)가 큐가 비는 시간(약 37초)보다 10배 이상 짧았다. 그래서 일시적인 버스트가 영구 실패로 굳었다.

### 수정
- **`EmbedWorkerConfig.BoundedWaitPolicy`:** 큐가 가득 차면 즉시 거절하지 않는다. 제출 스레드(poll 스레드)가 `submit-timeout-ms`(60초)까지 자리가 나길 기다리고, 그래도 없을 때만 거절해 기존 재시도/DLT 경로로 넘긴다.
- **`EmbedConsumer`의 `max.poll.records=10`:** 최악의 poll 간격은 "60초 × 레코드 수"다. 기본값 500이면 `max.poll.interval.ms`(15분)를 넘어 컨슈머가 그룹에서 쫓겨난다. 10이면 최악 10분이라 상한 안에 들어온다.

### 결과
같은 30건 버스트를 실제 수집 사이클로 재현했다. 큐 포화 거절은 0건이었고, 30건 모두 EMBEDDED가 됐다. 기존 FAILED 3건도 이때 복구됐다.

## 3. DLT 오프셋 미커밋 → 재기동마다 재처리

### 증상
앱이 기동하자마자 예전 DLT 레코드 3건이 다시 처리되면서 해당 레포를 FAILED로 되돌렸다. Kafka에서 확인해 보니 `embed-group-dlt`의 커밋된 오프셋이 없었다(`-`). 로그 끝 오프셋은 3이었다.

### 원인
컨테이너가 `AckMode.MANUAL`인데, `EmbedConsumer`와 `CollectConsumer`의 `@DltHandler` 모두 `Acknowledgment`를 받지 않았다. 오프셋이 한 번도 커밋되지 않았던 것이다. 그래서 앱이 뜰 때마다(그리고 `@SpringBootTest` 컨텍스트가 뜰 때마다) DLT를 처음부터 다시 읽었다. 그 사이 정상 임베딩된 레포까지 FAILED로 뒤집힐 수 있는 경합이었다.

### 수정
- 두 DLT 핸들러가 처리 후 `ack.acknowledge()`를 호출하게 했다. 단위 테스트도 추가했다.
- 이미 처리된 기존 DLT 레코드는 오프셋을 끝으로 옮겼다(`--reset-offsets --to-latest`). 이후 재기동해서 재처리가 0건인 것을 확인했다.

## 부하테스트 데이터 오염 주의

stub으로 트렌드 분석에 부하를 걸면 `[stub]` 텍스트가 `trend_scores`와 Redis 캐시(1시간)에 남는다.
부하테스트가 끝나면 **앱 프로세스가 완전히 종료된 뒤에** `scripts/loadtest-cleanup.sh`를 실행한다.
정상 종료는 포트를 먼저 닫고 남은 요청을 마저 처리하기 때문에, 그 전에 정리하면 행이 다시 생긴다. 실제로 이렇게 4건이 남았었다.
