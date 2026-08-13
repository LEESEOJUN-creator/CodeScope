# 코드 리뷰: Kafka 수집 파이프라인 + 배포 구성 (Day 18-21)

Day 18~21에 작성된 코드/설정 전체를 다시 읽고, 구조·흐름 정리와 함께
발견한 결함·위험 요소를 심각도 순으로 기록한다.

> **수정 완료(2026-08-14)**: 아래 지적사항 중 A, B, C, E, F, G, I, J를
> 반영했다. 항목별 처리 내용과 남은 과제는 문서 맨 끝
> [7. 수정 반영 결과](#7-수정-반영-결과) 참고.

---

## 1. 전체 구조와 데이터 흐름

### 1.1 런타임 흐름

```
[CollectScheduler]  @Scheduled(fixedDelay=10분)
      |
      | 1. githubApiClient.searchTrending()
      |    GET /search/repositories?q=stars:>1000 pushed:><6개월전>
      |        &sort=stars&order=desc&per_page=30
      v
  List<TrendingRepoDto>  (30건)
      |
      | 2. toCollectMessage() 변환
      | 3. collectProducer.publish(msg).get(5s)   ← 동기 확인
      v
  [Kafka: codescope.collect]  파티션 3, key=fullName
      |
      v
[CollectConsumer]  groupId=collect-group, concurrency=3
      |
      | 4. duplicateCheckService.tryLock(fullName)   ← Redis SETNX, TTL 10분
      |      false면 → ack 후 스킵
      | 5. GithubRepository 생성 → save()
      | 6. embedProducer.publish(fullName)          ← fire-and-forget
      | 7. ack.acknowledge()
      v
  [Kafka: codescope.embed]  파티션 3, key=fullName
      |
      v
[EmbedConsumer]  groupId=embed-group, concurrency=3
      |
      | 8. githubApiClient.fetchReadme(fullName)
      | 9. log.info(길이) → ack.acknowledge()
      v
   (Day 25에서 임베딩 생성 + pgvector 저장 예정)
```

### 1.2 계층별 책임

| 패키지 | 클래스 | 책임 |
|---|---|---|
| `scheduler` | `CollectScheduler` | 10분 주기 트리거, GitHub 조회 → Kafka 발행, 실패분 1회 재시도 |
| `infra.github` | `RestClientConfig` | `githubRestClient` Bean (baseUrl, Authorization, Accept) |
| | `GithubApiClient` | `searchTrending()`, `fetchReadme()`, Rate Limit 헤더 로깅 |
| | `GithubSearchResponse` / `TrendingRepoDto` | Search API 응답 envelope / items 매핑 |
| `kafka.dto` | `CollectMessage` / `EmbedMessage` | 토픽별 메시지 계약(record) |
| `kafka.producer` | `CollectProducer` / `EmbedProducer` | 토픽 발행, `CompletableFuture` 반환 |
| `kafka.consumer` | `CollectConsumer` | 멱등 처리(Redis 락 + DB unique) 후 저장, embed 이벤트 발행 |
| | `EmbedConsumer` | README 수집(뼈대) |
| `infra.config` | `KafkaTopicConfig` | collect(3)/embed(3)/dlt(1) 토픽 정의 |
| | `KafkaConsumerConfig` | AckMode.MANUAL 강제 |

### 1.3 배포 구성

```
GitHub PR  ──▶ ci.yml [test]           Postgres/Redis 서비스 컨테이너 + ./gradlew test
main push  ──▶ ci.yml [build-and-push] Dockerfile 빌드 → GHCR :<커밋SHA> push
                                              |
Git(main) ◀── kustomize edit set image        | (현재는 수동)
   |
   v
[ArgoCD Application]  path=k8s/overlays/kind, automated(prune+selfHeal)
   |
   v
kind 클러스터(default ns): codescope / postgres / redis / kafka
```

---

## 2. 발견된 결함 · 위험 (심각도 순)

### 🔴 A. Redis 락 미해제로 인한 메시지 유실 (가장 심각)

**위치**: `CollectConsumer.consume()` + `DuplicateCheckService`

Day 21에 `finally` 블록을 제거해 "재시도가 필요한 실패는 커밋하지 않는다"를
만들었는데, **Redis 락이 그 설계를 무력화한다.**

재현 시나리오:

1. `tryLock("owner/repo")` 성공 → Redis에 **TTL 10분** 락 설정
2. `save()`가 `DataIntegrityViolationException`이 아닌 예외로 실패
   (DB 커넥션 끊김 등) → catch 안 됨 → 예외 전파 → **ack 없음** (의도대로)
3. Kafka 컨테이너가 **같은 레코드를 즉시 재전달**
4. 재전달분이 `tryLock()`을 다시 호출 → 락이 아직 살아있으므로 **false**
5. → "이미 처리 중이라 스킵" 로그 + **`ack.acknowledge()` 호출** → 커밋
6. **결과: 레포는 저장되지 않았는데 메시지는 커밋되어 영구 소실**

즉 "DB 장애 시 재시도해서 살린다"는 목표가, 정확히 그 DB 장애 상황에서
반대로 동작한다. `releaseLock()`은 구현되어 있으나 **운영 코드 어디에서도
호출되지 않는다**(테스트 `@AfterEach`에서만 사용).

수정 방향 후보:
- 처리 실패(재시도가 필요한 예외) 시 `releaseLock()` 후 예외를 다시 던지기
- 또는 락을 "처리 중" 표식으로만 쓰고, 성공 시점에 별도 "완료" 키로 전환

### 🔴 B. 에러 핸들러/DLT 미연결 — "예외 던지면 재전달"은 무한 보장이 아님

**위치**: `KafkaConsumerConfig`(에러 핸들러 미설정), `EmbedConsumer`,
`CollectConsumer`

현재 `DefaultErrorHandler`(Spring Kafka 기본값)가 적용된다. 기본 동작은
**백오프 없이 즉시 10회 재시도 → 그래도 실패하면 로그만 남기고 해당 레코드의
오프셋을 커밋하고 다음으로 넘어감**이다(`ackAfterHandle` 기본 true).

따라서 문서/주석에 적힌 "예외를 던져서 Kafka 재전달에 맡긴다"는 서술은
**부분적으로만 참**이다. 실제로는 짧은 순간에 10번 시도한 뒤 메시지가
조용히 버려진다. `KafkaTopicConfig`에 `codescope.dlt` 토픽이 정의되어
있지만 **어디에도 연결되어 있지 않다.**

특히 `EmbedConsumer`는 이 문제에 직접 노출된다:
- README가 없는 레포는 GitHub이 **404를 영구적으로** 반환
- → 절대 성공할 수 없는 요청을 10회 반복 → 버려짐
- → Rate Limit만 갉아먹고 기록도 남지 않음

수정 방향: `@RetryableTopic` 또는 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
연결, 그리고 404처럼 **재시도해도 소용없는 예외**는 재시도 대상에서 제외.

### 🟠 C. 기존 레포 갱신 경로가 없음 — 2번째 사이클부터 파이프라인이 무의미

**위치**: `CollectConsumer.consume()`, `GithubRepository.update()`

스케줄러는 10분마다 "별 1000개 이상 + 최근 6개월 활성" 상위 30건을 가져온다.
이 목록은 사이클 간 **거의 바뀌지 않는다**. 그런데 컨슈머는 신규 `save()`만
하고, 이미 있으면 `DataIntegrityViolationException`을 잡아 스킵한다.

결과:
- 첫 사이클에 30건 저장
- 이후 모든 사이클: 전부 중복 스킵 → **아무 일도 일어나지 않음**
- `starCount`/`forkCount`/`openIssueCount`는 **최초 수집값에 영원히 고정**

`GithubRepository.update(starCount, forkCount, openIssueCount)` 메서드가
이미 존재하지만 **호출하는 곳이 없다.** "실시간 트렌드 수집"이라는 서비스
정체성과 직접 충돌하는 공백이다.

수정 방향: `findByFullName()` 후 존재하면 `update()`, 없으면 `save()`
(단, 이때 Redis 락 TTL 10분이 갱신 주기를 막으므로 A와 함께 재설계 필요).

### 🟠 D. ProcessStatus가 갱신되지 않음

**위치**: `GithubRepository.markEmbedded()` / `markFailed()`

두 메서드 모두 **호출처가 없다.** 저장되는 모든 레포는 `COLLECTED`에
머문다. CLAUDE.md에 명시된 "RAG 추천 쿼리는 반드시 status=EMBEDDED만
필터링"이라는 설계는 현재 상태로는 **결과가 항상 0건**이 된다.

Day 25(임베딩 구현) 범위이므로 지금 결함은 아니지만, 그때 반드시 함께
채워야 하는 항목으로 기록해 둔다.

### 🟠 E. 테스트 실행 시 스케줄러가 실제로 동작함

**위치**: `CodescopeApplication`의 `@EnableScheduling` + `@SpringBootTest`

`@Scheduled(fixedDelay = ...)`는 `initialDelay`가 없으면 **컨텍스트 기동
직후 즉시 1회 실행**된다. 현재 `@SpringBootTest`를 쓰는 테스트가 4개
(`CodescopeApplicationTests`, `TrendServiceTest`, `DuplicateCheckServiceTest`,
`DbBackpressureLoadTest`) 있으므로, **테스트를 돌릴 때마다 실제 GitHub API를
호출하고 실제 Kafka에 발행을 시도한다.**

- 로컬(유효한 `GITHUB_TOKEN` 보유): 테스트 한 번에 실제 수집이 일어나
  개발 DB에 데이터가 들어감
- CI(더미 토큰): 401로 실패 → `log.error` 후 사이클 스킵이라 죽지는 않지만,
  테스트가 외부 네트워크에 의존하는 구조 자체가 바람직하지 않음

수정 방향: 스케줄러 클래스에 `@Profile("!test")`를 걸거나, 테스트용
프로퍼티로 스케줄링을 비활성화.

### 🟡 F. CI가 대규모 부하 테스트를 매 PR마다 실행

**위치**: `.github/workflows/ci.yml`의 `./gradlew test`

`DbBackpressureLoadTest`는 `LOAD_SIZE = 2000`, `REPEAT_PER_THREAD = 5`로
가상 스레드 2000개를 띄우는 부하 테스트다. 과거 실측 기록상
세마포어 없는 케이스가 **62.5초**, 있는 케이스가 12.6초 걸렸다.

이 클래스는 별도 `dbBackpressureJfrTest` 태스크로도 등록되어 있지만
**기본 `test` 태스크에서 제외되어 있지 않아**, 모든 PR에서 실행된다.
CI 시간이 길어지고, 러너 성능 편차에 따라 결과가 흔들릴 수 있다.

수정 방향: JUnit `@Tag`로 분리 후 기본 `test`에서 제외.

### 🟡 G. Kafka 장애 시 스케줄러 스레드 장시간 점유

**위치**: `CollectScheduler.publishAll()`

브로커가 죽으면 30건 × 5초 타임아웃 × 2라운드(1차 + 재시도) = **최대 300초**
동안 스케줄러 스레드가 블로킹된다. Spring 기본 `TaskScheduler`의 풀 크기는
**1**이므로, 향후 다른 `@Scheduled` 작업을 추가하면 그 뒤로 줄줄이 밀린다.

`fixedDelay`라 자기 자신과 겹치지는 않으므로 현재는 견딜 만하지만,
스케줄러가 늘어나기 전에 풀 크기 조정 또는 타임아웃/재시도 예산 축소가 필요.

### 🟡 H. Redis 락 TTL(10분)과 스케줄러 주기(10분)가 동일

락 TTL과 수집 주기가 같으면 경계에서 동작이 불안정해진다. 락이 막 만료된
직후 다음 사이클이 도착하면 중복 처리 창이 열리고, 반대로 조금 일찍
도착하면 정상 갱신이어야 할 요청까지 스킵된다. C(갱신 경로) 설계 시 두 값의
관계를 명시적으로 정해야 한다.

### 🟢 I. 소소한 방어 로직 공백

| 위치 | 내용 |
|---|---|
| `GithubApiClient.fetchReadme()` | `fullName.split("/", 2)` 결과를 검증하지 않아, `/`가 없는 값이 들어오면 `ArrayIndexOutOfBoundsException`. 현재는 자체 파이프라인이 만든 값만 들어와 실질 위험은 낮음 |
| `GithubApiClient.logRateLimitRemaining()` | `Integer.parseInt()`가 비수치 헤더에 대해 `NumberFormatException`. 로깅용 부가 로직이 본 흐름을 죽일 수 있음 |
| `KafkaConsumerConfig` | 팩토리 제네릭이 `<String, String>`인데 실제 값 타입은 `CollectMessage`/`EmbedMessage`. 런타임엔 타입 소거로 동작하지만 코드를 읽는 사람을 오해시킴 |
| `CollectConsumer` | `embedProducer.publish()`가 fire-and-forget이라, 저장은 됐는데 embed 이벤트만 유실되면 해당 레포는 영원히 임베딩되지 않음(State Drift). Day 25 재검토 대상으로 이미 주석에 명시됨 |

### 🟢 J. 배포 구성 관련 메모

- **이미지 태그 수동 갱신**: `k8s/overlays/kind/kustomization.yaml`의
  `images.newTag`가 특정 SHA로 고정되어 있다. 다음 커밋이 GHCR에 올라가도
  **자동으로 반영되지 않으므로** 매번 수동 갱신이 필요하다. CI 마지막
  단계에서 `kustomize edit set image` + commit/push를 붙이면 자동화된다.
- **`codescope-secrets`는 git에 없음**: 수동 생성 대상이다. ArgoCD의
  `prune: true`는 **ArgoCD가 추적하는 리소스만** 삭제하므로, 수동 생성한
  이 Secret이 자동 sync 때문에 지워지지는 않는다.
- **Secret 누락 시 증상**: Secret이 없으면 이미지 pull이 성공해도 Pod가
  `CreateContainerConfigError`로 멈춘다. `ImagePullBackOff`와는 다른 증상이니
  구분해서 볼 것.
- **`jwt.cookie-secure`**: 클러스터 배포에서도 기본값 `false`다. HTTPS를
  붙이는 시점에 `JWT_COOKIE_SECURE=true`를 Secret/env에 추가해야 한다.

---

## 3. 우선순위 제안

| 순서 | 항목 | 이유 |
|---|---|---|
| 1 | **A. 락 미해제 유실** | 데이터 유실. Day 21에 세운 커밋 설계를 정면으로 깨뜨림 |
| 2 | **C. 갱신 경로 없음** | 서비스 핵심 가치("실시간 트렌드")가 동작하지 않음 |
| 3 | **B. DLT 연결** | 유실 방지의 마지막 안전망. A와 함께 봐야 설계가 맞음 |
| 4 | **E. 테스트 중 스케줄러 실행** | 테스트가 외부 API/실 DB를 건드림 |
| 5 | F, G, H, I | 운영 편의·견고성 |
| — | D | Day 25 임베딩 구현과 함께 |

A와 C는 서로 얽혀 있다(락 TTL이 갱신 주기를 막음). **따로 고치지 말고
"멱등성 + 갱신" 정책을 한 번에 재설계**하는 편이 낫다.

---

## 7. 수정 반영 결과

| 항목 | 상태 | 처리 내용 |
|---|---|---|
| A. 락 미해제 유실 | 해결 | 락을 `lock:processing`(TTL 1분) / `lock:completed`(TTL 9분)로 분리. 실패 경로에서 `markCompleted()`를 호출하지 않아 재시도가 살아남 |
| B. DLT 미연결 | 해결 | `@RetryableTopic`(3회, 지수 백오프) + `@DltHandler`. 4xx는 `exclude`로 즉시 DLT. 죽은 `codescope.dlt` 정의 제거 |
| C. 갱신 경로 없음 | 해결 | `findByFullName()` 후 존재 시 `update()`, 없으면 `save()` |
| D. ProcessStatus | 보류 | Day 25 임베딩 구현과 함께 처리 예정 |
| E. 테스트 중 스케줄러 실행 | 해결 | `@Profile("!test")` + test 태스크에 `spring.profiles.active=test` |
| F. CI 부하 테스트 | 해결 | `@Tag("load")` + `excludeTags`, 별도 `loadTest` 태스크 |
| G. 스케줄러 블로킹 | 해결 | 타임아웃 5초→3초(최악 300초→180초), `pool.size: 2` |
| H. TTL == 주기 | 해결 | 완료 표식 TTL을 9분으로 주기(10분)보다 짧게 |
| I. 방어 로직 | 해결 | `fullName` 형식 검증, Rate Limit 파싱 실패 무시, 팩토리 제네릭 `<String, Object>` |
| J. 태그 수동 갱신 | 해결 | CI가 GHCR push 후 `newTag`를 자동 갱신·커밋·push |

### 남은 과제 (신규 발견)

**K. 재시도 백오프와 처리 중 락 TTL이 어긋난다**

`@RetryableTopic`의 백오프는 1초 → 2초인데, `lock:processing`의 TTL은
1분이다. 즉 처리에 실패해 재시도가 오더라도 **모든 재시도 시점(1초, 2초)에
락이 아직 살아있어** `tryLock()`이 false를 반환하고, 저장되지 않은 채
DLT로 넘어간다. A를 고치면서 "락이 자연 만료되어 재시도 가능해진다"고
설계했는데, 실제 재시도 간격이 TTL보다 훨씬 짧아 그 전제가 성립하지 않는다.

해결 후보(택일 필요):
1. 실패 시 `lock:processing`을 명시적으로 삭제하고 예외를 던진다 —
   재시도가 즉시 가능해지지만, "락 해제 책임"이 다시 코드로 돌아온다
2. 백오프를 `lock:processing` TTL보다 길게 잡는다(예: delay 60초) —
   설정만으로 정합성이 맞지만 실패 복구가 느려진다
3. `lock:processing` TTL을 백오프보다 짧게(예: 10초) 줄인다 —
   정상 처리가 10초를 넘으면 중복 처리 위험

**L. 공유 개발 DB 오염으로 기존 테스트 5건 실패**

클러스터의 파이프라인이 로컬 테스트와 **같은 PostgreSQL**에 실제 수집
데이터를 쌓고 있다(현재 `github_repository` 31건). `@DataJpaTest`에
`replace = NONE`으로 실 DB를 쓰는 테스트들이 자기 픽스처만 있다고 가정해
개수를 단언하므로 깨진다(`expected: 3L but was: 34L`).

이번 리팩터링과 무관한 환경 문제지만, 파이프라인이 실제로 돌기 시작하면서
드러난 구조적 문제다. Testcontainers 도입이나 테스트 전용 스키마 분리가
필요하다.
