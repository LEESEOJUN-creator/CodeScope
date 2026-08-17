# DB 커넥션 풀 배압(backpressure) 실험

목적: `DbSemaphoreConfig`의 세마포어(그리고 그 안의 reserve 예약분)가
HikariCP 커넥션 풀 고갈 상황에서 실제로 의미 있는 보호 효과를 내는지
실측으로 확인한다.

## 트러블슈팅 타임라인 (2026-08-17)

부하 테스트 인프라를 준비하고 첫 실험을 돌리는 과정에서 아래 문제들을
차례로 만났고, 각각의 원인/조치를 남긴다. 실험 결과 자체만큼 이 과정도
"왜 처음 나온 숫자를 못 믿고 폐기했는가"를 설명하는 데 필요해서 같이 기록한다.

1. **Docker Desktop이 통째로 꺼져 있었음** — InfluxDB/Grafana(loadtest 스택)뿐
   아니라 kind 클러스터(`codescope-control-plane`, postgres/redis/kafka Pod)까지
   전부 내려간 상태였다. `docker compose up -d` + `scripts/local-up.ps1`로 재기동.

2. **`.claude/hooks/block-sensitive.py` 훅이 상대경로 문제로 Read/Edit/Bash를
   전부 막음** — 훅 커맨드가 `.claude/hooks/block-sensitive.py`(상대경로)였는데,
   Claude Code 작업 디렉토리가 서브디렉토리(`infra-monitoring`)로 바뀐 채
   실행되면서 스크립트를 못 찾아 보안 장치가 오히려 전체 작업을 마비시켰다.
   `$CLAUDE_PROJECT_DIR` 환경변수로 절대경로화해서 해결(커밋 `e190434`).

3. **`POST /api/test/simulate-batch-load`가 401** — `SecurityConfig`에 이
   경로에 대한 `permitAll` 규칙이 없어 JWT 없는 JMeter/k6 호출이 막혔다.
   `Environment.acceptsProfiles(Profiles.of("test"))` 조건으로 test 프로파일에서만
   `permitAll`이 활성화되도록 추가(커밋 `9833735`) — 운영 환경에는 이 규칙
   자체가 등록되지 않는다(컨트롤러도 `@Profile("test")`라 이중 방어).

4. **`GET /api/repos/1` 404** — JMeter의 `REPO_ID` 기본값(1)이 실제 DB에
   없는 id였다. `github_repository` 테이블 PK가 62부터 시작(`SELECT
   github_repository_id, full_name FROM github_repository ORDER BY
   github_repository_id LIMIT 5` 실측 결과: 62=sindresorhus/awesome,
   63=public-apis/public-apis, 64=openclaw/openclaw,
   65=EbookFoundation/free-programming-books,
   66=donnemartin/system-design-primer). `REPO_ID=62`로 수정(커밋 `f0986c8`).
   **이 버그 때문에 나온 최초의 "실험 A" 결과(30초 타임아웃, 100% 실패)는
   세마포어/배압과 무관한 404 소음이었으므로 전량 폐기한다.**

5. **`codescope-repo-detail.jmx` 손상** — JMeter GUI에서 저장하는 과정에
   `Thread Group("Repo Detail Threads")`와 그 안의 `HTTP Request`,
   `Summary Report`가 통째로 사라진 채 저장됨. 직전 정상 커밋(`645636c`)이
   git에 남아있어 `git checkout`으로 원본 그대로 복원, 그 위에 REPO_ID만
   62로 재수정.

## 실험 재정의

`LoadTestController.simulateBatchLoad()`에 `useSemaphore`(기본값 `true`) 파라미터를
실제로 추가했다(2026-08-17). `false`면 `dbSemaphore.acquire()/release()` 블록
자체를 건너뛰고 바로 `dataSource.getConnection()` → `pg_sleep(durationMs)`를
실행한다 — 같은 엔드포인트, 같은 파라미터 형태로 "세마포어 있음/없음"을
직접 대조할 수 있게 했다.

| 실험 | 조건 | 목적 |
|---|---|---|
| 실험 A (신) | `useSemaphore=false` | 배압 제어가 전혀 없는 상태 — count가 HikariCP maximumPoolSize(10)를 넘으면 connection-timeout(기본 30s)으로 실패가 발생하는지 확인 |
| 실험 B (신) | `useSemaphore=true`, reserve=2(기본, permits=8) | 세마포어가 초과분을 "실패"가 아니라 "대기"로 바꿔주는지 확인 |
| 실험 C | `useSemaphore=true`, reserve=0(permits=10, 임시 변경) | 예약분(reserve) 자체가 정말 의미 있었는지 — reserve 없이도 결과가 같다면 reserve의 존재 이유를 재검토해야 함 |

## 결과

실험 A/B는 `count=50, durationMs=8000`으로 `simulate-batch-load`를 배경에
쏘면서, 그와 **동시에 JMeter(`codescope-repo-detail.jmx`, `GET /api/repos/62`
반복 호출)로 세마포어를 거치지 않는 경로가 얼마나 영향받는지 관찰**한 값이다.
아래 수치는 JMeter Summary Report(평균/최대/표준편차/처리량) 기준.

> **참고**: `simulate-batch-load` 자체의 curl 응답(`{requested, succeeded,
> failed, totalElapsedMs}`)은 이번엔 별도로 전달받지 못했다. 배치 자체의
> 성공/실패 여부는 이 문서에 아직 비어 있음 — 다음에 curl 원문이 확보되면
> 채워 넣을 것.

### 실험 A (`useSemaphore=false`, 배압 없음) — GET /api/repos/62 관찰 지표

```
평균: 160ms
최대: 1453ms
표준편차: 173.93
처리량: 41.5 (req/s 또는 초당 처리량, JMeter Summary Report 단위)
```

### 실험 B (`useSemaphore=true`, reserve=2, permits=8) — GET /api/repos/62 관찰 지표

```
평균: 16ms
최대: 325ms
표준편차: 21.87
처리량: 89.2
```

### 실험 C (`useSemaphore=true`, reserve=0, permits=10) — GET /api/repos/62 관찰 지표

```
평균: 9ms
최대: 326ms
표준편차: 15.73
처리량: 99.5
```

## 요약 표

| 실험 | useSemaphore | reserve | permits | GET 평균(ms) | GET 최대(ms) | GET 표준편차 | GET 처리량 |
|---|---|---|---|---|---|---|---|
| A | false | - | - | 160 | 1453 | 173.93 | 41.5 |
| B | true | 2 | 8 | 16 | 325 | 21.87 | 89.2 |
| C | true | 0 | 10 | 9 | - | - | 99.5 |

**해석 — A vs B (세마포어 유무)**: 세마포어가 켜졌을 때(B) 세마포어를
전혀 거치지 않는 `GET /api/repos/{id}`조차 평균 응답시간이
160ms → 16ms(약 10배), 최대값이 1453ms → 325ms(약 4.5배)로 개선되고
표준편차도 크게 줄었다(173.93 → 21.87). 처리량도 41.5 → 89.2로 약 2.1배
늘었다. "세마포어가 보호하는 건 특정 요청이 아니라 HikariCP 풀이라는
공유 자원 자체"라는 설계 의도(`infra-monitoring/README.md`)가 실측으로
뒷받침된다.

**해석 — B vs C (reserve 유무, permits 8 vs 10)**: **가설과 반대로 나왔다.**
reserve=0(permits=10, C)이 reserve=2(permits=8, B)보다 오히려 평균
응답시간이 낮고(9ms vs 16ms) 처리량도 높았다(99.5 vs 89.2). 예약분이
있을수록 GET이 보호받을 거라는 가설은 이번 실험 조건에서는 **뒷받침되지
않았다.**

가능한 원인(원인 조사, 확정적 실측 아님 — 아래는 추론):
- **이번 실험엔 `HikariPoolMXBean.getThreadsAwaitingConnection()` 계측이
  없었다.** 이 계측은 별도 통합 테스트
  (`src/test/java/com/codescope/infra/config/DbBackpressureLoadTest.java`,
  `@Tag("load")`)에만 존재하고 `LoadTestController`엔 연결되어 있지
  않아서, 실행 당시 실제 pending 스레드 수를 로그로 되짚어볼 수 없다.
  이 원인 후보는 데이터 부재로 검증 불가 — 확인하려면 다음 실험에
  `LoadTestController`에도 같은 샘플링을 붙여야 한다.
- **`durationMs=8000`이 웨이브 길이 자체를 지배적으로 만들었을 가능성이
  높다.** permits=8(B)은 7웨이브 × 8000ms ≈ 총 54초, permits=10(C)은
  5웨이브 × 8000ms ≈ 총 40초로 배치 전체가 끝나는 시간이 14초나 차이난다.
  "언제든 여유 슬롯이 몇 자리 있는가"(reserve가 큰 B가 유리)보다
  "경합 구간 자체가 얼마나 긴가"(웨이브 수가 적은 C가 유리)가 이번
  파라미터 조합에서는 더 크게 작용한 것으로 보인다 — 즉 reserve의 보호
  효과가 없다기보다는, 이번 count/durationMs 조합이 그 효과를 관찰하기에
  적합하지 않았을 가능성이 있다.
- B와 C는 서로 다른 시점의 앱 재시작(재컴파일 후 재시작)이라 JVM/HikariCP
  워밍업 조건이 완전히 동일하지 않았고, 반복 시행 없이 단일 실행값이라
  9ms vs 16ms 차이가 통계적으로 유의미하다고 단정하기엔 표본이 부족하다.

**결론**: reserve의 효과는 이번 실험 조건(count=50, durationMs=8000,
단일 시행)에서는 뚜렷하게 관찰되지 않았다. 세마포어의 존재 자체(무질서한
동시 요청을 질서 있게 만드는 것)가 reserve 값(8 vs 10)보다 더 지배적인
요인으로 보인다. reserve 값 자체의 순수 효과를 제대로 보려면 (1)
`LoadTestController`에 pending 스레드 샘플링을 추가하고, (2) durationMs를
줄여 웨이브 수 차이가 총 실행시간을 과도하게 좌우하지 않는 조합으로,
(3) 반복 시행으로 재실험하는 게 필요하다 — 이번 스코프에서는 "세마포어
유무"의 효과를 입증하는 것까지가 확실한 성과이고, reserve 세부 튜닝은
추가 실험 과제로 남긴다.

## k6 실행 결과 (repo-detail.js, stages 적용 후)

세마포어가 정상 활성화된 상태(reserve=2, permits=8)에서 개선된
`repo-detail.js`(10s ramp-up → 30s steady 10 VUs → 10s ramp-down,
`useSemaphore` 개념과 무관하게 `GET /api/repos/62` 자체를 부하 대상으로 함)를
`k6 run --out influxdb=http://localhost:8086/loadtest repo-detail.js`로 실행.

### 터미널 원문 (요약)

```
✓ 'p(95)<500' p(95)=285.21ms
✓ 'rate<0.01' rate=0.00%

checks_total.......: 371     checks_succeeded...: 100.00% (371/371)

http_req_duration: avg=109.27ms min=7.61ms med=37.7ms max=3.26s
                   p(90)=189.45ms p(95)=285.21ms
http_req_failed:   0.00% (0/371)
http_reqs:         371   7.277143/s
vus_max:           10
iterations:        371   7.277143/s
```

두 threshold(`p(95)<500ms`, `rate<0.01`) 모두 통과. 실패 0건.

### 대조 발견 — Grafana 표시값이 이 실행의 값이 아니었다

k6 실행 직후 열어본 K6 Dashboard(Grafana)에 `Average Response: 246.29ms`,
`Max Response: 3.26s`, `Request Made: 168`이 떴다. 처음엔 "Grafana가 다른
집계 시간창을 써서 평균이 다르게 나온 것"으로 추정했으나, **InfluxDB
원본을 직접 조회해서 확인한 결과 그 추정은 틀렸다**:

```sql
-- InfluxDB에 실제로 존재하는 name='repo-detail' 레코드 전체
SELECT count("value") FROM "http_req_duration" WHERE "name" = 'repo-detail'
→ 168개 (전체 기간 통틀어 168개뿐, 여러 런의 합산이 아니라 이게 전부)

SELECT mean("value"), max("value"), min("value")
FROM "http_req_duration" WHERE "name" = 'repo-detail'
→ mean=134.59ms, max=3261.6656ms(=3.26s), min=7.6122ms(=7.61ms)
```

`min`(7.61ms)과 `max`(3.2616s)는 터미널 출력과 **정확히 일치**한다 —
즉 InfluxDB에 쌓인 168개는 방금 그 실행과 **같은 런**이 맞다. 그런데
터미널은 `http_reqs=371`, `iterations=371`이라고 보고했는데 InfluxDB엔
168개(약 45%)만 도착했다. **약 55%의 데이터 포인트가 k6 → InfluxDB
전송 과정에서 유실됐다.** (평균값 차이도 이걸로 설명된다: 유실된 표본이
무작위가 아니라면 mean이 109.27ms → 134.59ms로 달라질 수 있다.)

원인은 확정하지 못함(추가 조사 필요) — 가능성 후보:
- k6의 InfluxDB v1 출력(xk6-output-influxdb)은 내부적으로 일정 주기로
  메트릭을 배치 flush하는데, 짧은 테스트(총 50초)에서 마지막 배치가
  프로세스 종료 전에 다 못 나가고 버려졌을 가능성
- InfluxDB 1.8 쓰기 처리량이 순간적으로 못 따라가 일부 쓰기가 조용히
  실패했을 가능성(k6는 출력 실패를 기본적으로 test 실패로 만들지 않음)

**실무 시사점**: k6 터미널 요약(`k6 run`이 그 자리에서 보여주는 값)이
threshold 판정의 기준이자 **가장 신뢰할 수 있는 원본**이고, Grafana는
그걸 보조하는 시각화일 뿐 — 이번처럼 전송 유실이 있으면 Grafana 쪽 숫자가
더 적게/다르게 나올 수 있다는 걸 실측으로 확인했다. CI에서 pass/fail을
판단할 땐 반드시 k6 자체의 exit code/threshold 결과를 기준으로 삼아야
하고, Grafana 대시보드 숫자를 판정 근거로 쓰면 안 된다.

### 해석 요약

- **threshold 통과**: `p(95)=285.21ms < 500ms`, `실패율 0% < 1%` — 오늘
  세마포어 활성 상태의 API가 실무 기준 회귀 게이트를 통과하는 "건강한
  상태"임을 자동 판정으로 확인
- **max=3.26s 아웃라이어**: p95는 통과했지만 최댓값 하나가 유독 크다.
  원인 미확인(추가 조사 필요) — 첫 요청의 JIT/커넥션 풀 워밍업, 혹은
  그 순간 우연히 다른 부하가 겹쳤을 가능성 등 후보만 있고 확정 안 됨
- **k6 → InfluxDB 데이터 유실(약 55%)**: 이번 세션에서 새로 발견한
  이슈. 판정은 항상 k6 자체 출력 기준으로 하고, Grafana는 참고용으로만
  쓸 것
