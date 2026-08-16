# Day 35 부하 테스트 인프라

사람이 직접 JMeter GUI / Grafana / JDK Mission Control로 결과를 보는 게 목표.
이 문서는 그 세 도구를 실제로 켜고 연결하는 방법과, 왜 이렇게 설계했는지를 남긴다.

## 구성

```
infra-monitoring/
├── docker-compose.yml          # InfluxDB(1.8) + Grafana(latest), codescope 앱과 무관
├── grafana/provisioning/
│   ├── datasources/influxdb.yml    # InfluxDB를 자동 등록(수동 Add Data Source 불필요)
│   └── dashboards/
│       ├── dashboards.yml          # 파일 기반 프로비저닝 설정
│       ├── jmeter-dashboard-4026.json   # Grafana Labs 공식 대시보드(ID 4026), datasource 치환 완료
│       └── k6-dashboard-14801.json      # Grafana Labs 공식 대시보드(ID 14801), datasource 치환 완료
└── loadtest/
    ├── codescope-repo-detail.jmx   # JMeter 테스트 플랜
    └── repo-detail.js              # k6 스크립트
```

## 실험 설계 — 왜 GET /api/repos/{id}는 세마포어를 안 거치는가

`GET /api/repos/{id}`는 **의도적으로 세마포어를 거치지 않는 경로로 유지한다**.
이는 세마포어를 직접 거치는 요청 간 경합을 보려는 게 아니라, **세마포어와 무관한
가벼운 조회조차 DB 커넥션 풀(HikariCP) 고갈의 간접 영향을 받는지**를 확인하기
위함이다. 즉 세마포어가 보호하는 것은 특정 요청이 아니라 **HikariCP 커넥션 풀이라는
공유 자원 자체**다.

그래서 부하 테스트는 두 축으로 나뉜다:

1. **배압 발생원**: `POST /api/test/simulate-batch-load?count=&durationMs=`
   (`LoadTestController`, `@Profile("test")`) — `dbSemaphore.acquire()` →
   실제 DB 커넥션을 얻어 `SELECT pg_sleep(durationMs)`로 durationMs만큼 진짜 점유 →
   `release()`. count를 permits(HikariCP maximumPoolSize - reserve)보다 훨씬 크게
   주면 세마포어가 초과분을 "실패"가 아니라 "대기"로 바꿔주는지 확인 가능.
2. **관찰 대상**: JMeter/k6가 `GET /api/repos/{id}`를 반복 호출. 세마포어를
   안 거치므로, 배압 발생원이 없을 때는 항상 빠르게 응답해야 정상이다. 배치
   부하를 동시에 걸었을 때 이 요청의 지연/실패가 늘어난다면, 그건 세마포어
   자체의 문제가 아니라 **HikariCP 풀이 실제로 고갈되고 있다**는 뜻 —
   `DbSemaphoreConfig`의 reserve(기본 2)가 이런 상황을 막기 위한 예약분이다.

## 1. 모니터링 스택 기동

```powershell
cd infra-monitoring
docker compose up -d
```

확인 완료(실측):
- InfluxDB: `curl http://localhost:8086/ping` → `204 No Content`
- `loadtest` DB 자동 생성 확인: `SHOW DATABASES` → `loadtest`, `_internal`
- Grafana: `curl http://localhost:3001/login` → `200`(최초 기동 시 플러그인 설치로
  10~20초 정도 걸릴 수 있음 — 바로 000이 나오면 몇 초 뒤 재시도)
- Grafana 데이터소스 자동 등록 확인: `GET /api/datasources` → `InfluxDB-loadtest`
- Grafana 대시보드 자동 프로비저닝 확인: `GET /api/search` → `JMeter Dashboard`,
  `K6 Dashboard`가 "Load Test" 폴더 아래 나타남(수동 Import 불필요)

## 2. 배치 부하 트리거

앱을 `test` 프로파일로 실행해야 `LoadTestController`가 뜬다(운영 미노출용
`@Profile("test")`). IntelliJ Run Configuration의 VM options 또는 환경변수에
`-Dspring.profiles.active=test` 추가 — 이 프로파일은 `CollectScheduler`도
같이 꺼버리므로(`@Profile("!test")`) 부하 테스트 중 실제 GitHub 수집이
끼어들 걱정도 없다.

```
POST http://localhost:8080/api/test/simulate-batch-load?count=20&durationMs=3000
```

### 실측 검증 (2026-08-17)

**세마포어가 permits 초과분을 대기로 바꾸는지**: `count=15, durationMs=2000`
(permits=8이므로 2개 웨이브 예상: ceil(15/8)=2 → 이론상 ~4000ms)

```json
{"requested":15,"succeeded":15,"failed":0,"totalElapsedMs":4561}
```

15개 전부 성공(실패 0), 4561ms — 이론치(4000ms)에 근접. 세마포어가 없었다면
8개를 넘는 순간부터 HikariCP connection-timeout(기본 30s)으로 `failed`가
늘어났어야 한다.

**세마포어를 안 거치는 GET /api/repos/{id}가 배치 부하 중 실제로 영향받는지**:
`count=20, durationMs=5000`(permits=8 → 이론상 3웨이브, ~15000ms)를 백그라운드로
쏘면서 동시에 `GET /api/repos/77`을 5회 연속 호출:

```
배치 결과: {"requested":20,"succeeded":20,"failed":0,"totalElapsedMs":15318}

요청 1: HTTP 200, 7.157s   ← 배치 부하 초반, 크게 지연됨
요청 2: HTTP 200, 2.349s   ← 여전히 지연
요청 3: HTTP 200, 0.177s   ← 정상 속도로 복귀
요청 4: HTTP 200, 0.283s
요청 5: HTTP 200, 0.063s
```

**둘 다 실패(500/timeout) 없이 200으로 끝났지만, 초반 두 요청이 정상 대비
수십~수백 배 느려졌다.** 이게 이 실험이 보여주려던 핵심이다 — `/api/repos/{id}`는
세마포어 코드를 단 한 줄도 안 거치는데도, 배치 부하가 HikariCP 풀(max=10)의
커넥션을 대량으로 점유하는 동안에는 지연을 겪는다. 즉 세마포어가 지키는 건
"세마포어를 호출한 요청"이 아니라 HikariCP 풀 자체이고, reserve=2 예약분이
있어도 배치 동시성이 8(permits)까지 몰리면 그 예약분만으로 완전히 무손상을
보장하지는 못한다는 것도 이번 실측으로 확인됐다(요청 1~2가 지연된 이유).
reserve 값 조정 필요 여부는 더 많은 표본으로 별도 판단할 사안 — 이번 스코프는
"인프라가 뜨고 신호가 관찰되는가"까지였고, 그 목적은 달성됐다.

## 3. JMeter

1. JMeter 설치(미설치 시 https://jmeter.apache.org/download_jmeter.cgi , zip 받아서
   압축 풀고 `bin/jmeter.bat` 실행 — 별도 설치 프로그램 없음)
2. JMeter GUI 실행 후 **File > Open** → `infra-monitoring/loadtest/codescope-repo-detail.jmx`
3. 좌측 트리에서 **Test Plan**을 선택하면 `HOST`/`PORT`/`REPO_ID` 변수가 보임 —
   `REPO_ID`를 DB에 실제 존재하는 id로 바꿀 것(트렌드 목록에서 아무 레포나 확인)
4. 상단 초록 ▶ 버튼(Start)으로 실행. **View Results Tree**에서 개별 요청,
   **Summary Report**에서 집계를 바로 볼 수 있고, **Backend Listener**가
   같은 결과를 InfluxDB(`loadtest` DB)로도 실시간 전송한다
5. 배치 부하와 같이 보려면: JMeter 실행 직후(또는 도중) 위 2번 curl로
   `simulate-batch-load`를 같이 쏴서 Grafana에서 같은 시간대 그래프 비교

## 4. k6

1. 설치 안내: https://grafana.com/docs/k6/latest/set-up/install-k6/
   (Windows는 `winget install k6 --source winget` 또는 zip 압축 해제)
2. 실행:
   ```powershell
   k6 run --out influxdb=http://localhost:8086/loadtest infra-monitoring/loadtest/repo-detail.js
   ```
   REPO_ID를 바꾸려면: `k6 run --out influxdb=http://localhost:8086/loadtest -e REPO_ID=5 infra-monitoring/loadtest/repo-detail.js`

## 5. Grafana에서 결과 보기

`http://localhost:3001` 접속(익명 Admin 접속 허용해둠 — 로컬 전용이라 로그인 마찰 없앰) →
좌측 메뉴 **Dashboards** → **Load Test** 폴더 → **JMeter Dashboard** 또는
**K6 Dashboard** 선택. 우측 상단 시간 범위를 방금 테스트 돌린 구간(예: Last 15 minutes)으로 맞출 것.

## 6. JDK Mission Control (JFR)

1. JMC 설치: https://adoptium.net/jmc/ (Eclipse Adoptium이 배포하는 JMC — 지금 이
   프로젝트가 쓰는 JDK(Eclipse Adoptium/Temurin)와 같은 배포처라 궁합이 맞음)
2. JFR 레코딩 시작 — 두 가지 방법:
   - **미리 켜고 시작**(IntelliJ Run Configuration VM options에 추가):
     ```
     -XX:StartFlightRecording=duration=10m,filename=codescope-loadtest.jfr
     ```
   - **이미 떠 있는 프로세스에 즉석으로**(PowerShell):
     ```powershell
     jcmd <PID> JFR.start duration=5m filename=C:\codescope\codescope-loadtest.jfr
     ```
     PID는 `jcmd -l`로 확인. 앱을 `test` 프로파일로 IntelliJ에서 실행 중이면
     그 프로세스의 PID.
3. 부하 테스트(JMeter/k6 + simulate-batch-load) 실행
4. 레코딩 종료(정해둔 duration이 지나면 자동 종료, 즉시 끝내려면):
   ```powershell
   jcmd <PID> JFR.stop
   ```
5. JMC 실행 → **File > Open File** → 위에서 지정한 `.jfr` 파일 경로 열기
   (`-XX:StartFlightRecording` 방식이면 `filename`에 적은 경로 그대로,
   상대경로면 IntelliJ의 작업 디렉토리 = 프로젝트 루트 기준)
6. JMC 좌측 트리에서 **Threads**(가상 스레드 pinning 여부 — CLAUDE.md에서 언급된
   `jdk.VirtualThreadPinned` 이벤트), **Database**나 **Latency** 관련 이벤트가 아니라
   JMC 기본 뷰의 **General > Threads**, **Code > Hot Methods**, **Memory** 탭에서
   부하 구간의 스레드/GC 상태를 확인
