# 부하테스트 가이드 — k6 + Grafana + JFR + GC 로그

목표: 부하를 걸었을 때 **지연이 왜 늘었는지**를 한 원인으로 좁히는 것.
후보 원인은 네 가지다. 각각을 보는 도구가 다르다.

| 의심 원인 | 보는 곳 |
|---|---|
| GC pause (Stop-the-world) | Grafana "GC pause 최대" · JFR `gc-pauses` · GC 로그 |
| 세마포어 대기(배압) | Grafana "세마포어 대기 스레드 수" |
| DB 커넥션 풀 고갈 | Grafana "HikariCP 커넥션 pending" |
| 가상 스레드 pinning | JFR `pinned-threads` |

## 비용 원칙

`bootRunLoadTest`는 **stub LLM**(`StubLlmClient`/`StubEmbeddingService`)으로 뜬다.
API를 호출하지 않고 지연(기본: 생성 2초, 임베딩 200ms)만 재현하므로 비용은 0이다.
같은 세마포어를 거치기 때문에 배압 동작은 실제 경로와 동일하다.

실제 Claude 경로를 소량 확인할 때만 `LLM_GENERATION_PROVIDER=claude`로 덮어쓴다.
이때는 k6 요청 수만큼 비용이 나가므로 `RATE`/`DURATION`을 아주 작게 준다.

## 0. 사전 준비 (macOS)

```bash
brew install openjdk@21 k6
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
# Docker Desktop: https://www.docker.com/products/docker-desktop (GUI 설치)
```

앱 인프라(Postgres/Redis/Kafka)는 기존 kind 클러스터 + port-forward를 쓴다
(`scripts/local-up.ps1` 참고).

## 1. 모니터링 스택 기동

```bash
cd infra-monitoring && docker compose up -d
```

| 서비스 | 주소 | 용도 |
|---|---|---|
| Grafana | http://localhost:3001 | 대시보드 (로그인 없음) |
| Prometheus | http://localhost:9090 | 앱 지표 수집 (5초 간격) |
| InfluxDB | localhost:8086 | k6 결과 저장 |

Grafana "Load Test" 폴더:
- **CodeScope — JVM / GC / 배압**: 서버 측 관찰 (Prometheus)
- **k6 Load Testing Results**: 클라이언트 측 관찰 (InfluxDB)

## 2. 앱 기동 (JFR + GC 로그 + Prometheus)

```bash
export GITHUB_CLIENT_ID=... GITHUB_CLIENT_SECRET=... JWT_SECRET=...
./gradlew bootRunLoadTest              # G1, 힙 512m 고정
./gradlew bootRunLoadTest -Pgc=zgc     # 세대별 ZGC 비교 실험
./gradlew bootRunLoadTest -Pheap=1g    # 힙 크기 변경
```

이 태스크는 다음 상태로 앱을 띄운다.

- **프로파일 `test,loadtest`**: CollectScheduler가 꺼져 백그라운드 수집이 지표를 흔들지 않는다. stub LLM을 쓰고, `/actuator/prometheus`가 열린다. SQL 로그도 꺼진다(로그 문자열 할당이 GC 지표를 오염시키기 때문).
- **`-Xms`와 `-Xmx` 고정**: 힙 리사이징이 GC 측정에 섞이지 않는다.
- **산출물** (`build/loadtest/`):
  - `app-<gc>-<시각>.jfr`: JFR 녹화. 앱이 종료될 때 덤프된다.
  - `gc-<gc>-<시각>.log`: GC 통합 로그(`-Xlog:gc*,safepoint`).

Prometheus가 앱을 잘 수집하는지는 http://localhost:9090/targets 에서 `codescope`가 UP인지로 확인한다.

## 3. 부하 실행

```bash
# DB에 실존하는 레포 id를 넣는다
IDS=62,63,64,65,66

k6 run --out influxdb=http://localhost:8086/loadtest \
  -e REPO_IDS=$IDS infra-monitoring/loadtest/scenarios.js            # mixed, 2분

k6 run ... -e SCENARIO=browse    -e RATE=50  scenarios.js   # DB/캐시 경로만
k6 run ... -e SCENARIO=recommend -e RATE=40  scenarios.js   # RAG 경로(세마포어 포화 관찰)
k6 run ... -e SCENARIO=soak -e DURATION=30m  scenarios.js   # 장시간: 힙 누수 관찰
```

`scenarios.js`는 `constant-arrival-rate`(open model)를 쓴다. 서버가 느려져도 요청 유입이 줄지 않아서, coordinated omission 없이 p99를 측정할 수 있다.
`dropped_iterations`가 0보다 크면 서버가 목표 RPS를 감당하지 못했다는 뜻이다.

## 4. 분석

### Grafana에서 시간축 대조

먼저 k6 대시보드에서 p95/p99가 튄 시각을 찾는다. 그다음 JVM 대시보드의 같은 시각을 본다.

- **GC pause 최대**가 같은 시각에 튀었다면 → GC가 원인이다. 아래 JFR 분석으로 넘어간다.
- **세마포어 대기 스레드 수**가 쌓였다면 → 하류(LLM/임베딩) 처리량이 부족하다. permits와 하류 지연을 확인한다.
- **HikariCP pending**이 0보다 크다면 → 커넥션 풀이 고갈됐다. dbSemaphore 설정을 확인한다.
- **할당률**이 높고 Young GC가 잦다면 → 무엇을 할당하는지 JFR `allocation-by-site`로 확인한다.

### JFR 리포트

앱을 Ctrl+C로 종료하면 녹화가 덤프된다. 실행 중에 뽑으려면 `jcmd <pid> JFR.dump`를 쓴다.

```bash
scripts/jfr-report.sh build/loadtest/app-g1-<시각>.jfr
```

| 뷰 | 확인할 것 |
|---|---|
| `gc` / `gc-pauses` | GC 횟수, 최대 pause. p99 목표보다 큰 pause가 있는가 |
| `gc-pause-phases` | pause의 어느 단계가 긴가 (예: Object Copy → 살아남는 객체가 많음) |
| `allocation-by-class` / `allocation-by-site` | 할당 상위 클래스와 코드 위치 |
| `pinned-threads` | 가상 스레드 pinning 지점 |
| `contention-by-site` | 락 경합 |

같은 `.jfr` 파일을 JDK Mission Control로 열면 드릴다운할 수 있다.

> **예상되는 pinning 지점**: Claude SDK가 쓰는 OkHttp는 내부에 `synchronized` 블록이 있다. JDK 21에서는 이 블록 안에서 I/O를 기다리면 캐리어 스레드가 고정(pinning)된다. 이 고정은 JDK 24(JEP 491)에서 해소된다. 동시 호출 수는 claudeSemaphore(permits)가 상한이므로 고정되는 캐리어 수도 그 이하로 묶인다. 실제 Claude provider로 소량 부하를 걸어 `pinned-threads`에 나타나는지 확인할 가치가 있다. stub에서는 나타나지 않는다.

### GC 로그

`build/loadtest/gc-*.log`는 GCeasy(https://gceasy.io) 같은 분석기에 그대로 올릴 수 있다.
터미널에서 pause만 빠르게 보려면 다음 명령을 쓴다.

```bash
grep -E "Pause (Young|Full|Remark|Cleanup)" build/loadtest/gc-g1-*.log | tail -20
```

## 5. 실험 기록 템플릿

실험마다 조건을 한 가지만 바꾸고 아래 표를 채운다(`day35_backpressure.md`와 같은 방식).

| 실험 | GC | 힙 | 시나리오/RATE | p95 | p99 | GC 최대 pause | GC 횟수 | 할당률 | 세마포어 최대 대기 | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| A | G1 | 512m | mixed / 20 | | | | | | | 기준선 |
| B | ZGC | 512m | mixed / 20 | | | | | | | GC만 변경 |
| C | G1 | 512m | recommend / 40 | | | | | | | 세마포어 포화 |
