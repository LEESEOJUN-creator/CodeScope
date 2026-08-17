# 성능 측정 정리

측정일: 2026-08-16. 모든 값은 실측(로그 원문/curl 응답시간/`EXPLAIN ANALYZE`)이며,
추정치는 표에 섞지 않았다. 실측이 아닌 값은 별도로 "(2026-08-14 실측, 이전 세션)"처럼
출처를 명시했다.

## 측정 환경

- CPU: 12 logical processors, **GPU 없음**(Intel Iris Xe 내장 그래픽만 — Ollama는
  CPU-only 추론)
- OS: Windows 11
- Ollama: `llama3.2:3b`(생성), `nomic-embed-text`(임베딩), 로컬 실행(`127.0.0.1:11434`)
- DB/Redis/Kafka: kind 클러스터(k8s) Pod, `kubectl port-forward`로 로컬 접속
- **측정 중 동시에 떠 있던 프로세스**: IntelliJ IDEA, Microsoft Edge(여러 탭),
  Claude Code 세션 자체 — 이 환경이 "왜 이렇게 느린가"에 실제로 영향을 준다는 걸
  이전 세션에서 실측으로 확인했다(gradle 유휴 데몬 2개 정리 후 eval 속도가
  1.30 tok/s → 1.89 tok/s로 개선된 사례, 아래 표의 RAG 생성 항목 참고). 즉 아래
  수치는 "이 하드웨어 + 이 시점의 백그라운드 부하" 조건에서 나온 값이며, 전용
  서버·유휴 상태라면 더 나을 수 있다.
- 코드 상태: 이 문서 작성 시점 기준 커밋 `04d6ca1`(num_predict=200 적용,
  read-timeout 200초) + 이번 세션에서 추가한 pgvector 타이밍 로그

## 요약 표

| 항목 | 조건 | 실측값 |
|---|---|---|
| 임베딩(청크당) | nomic-embed-text, CPU (2026-08-14 실측, 이전 세션 — docs/troubleshooting.md) | 약 3.5초 |
| 임베딩(대형 레포) | 655청크(awesome-selfhosted/awesome-selfhosted), CPU (2026-08-14 실측, 이전 세션) | 약 38분 (655 × 3.5초) |
| pgvector 검색 (DB 쿼리 자체, `EXPLAIN ANALYZE`) | `repo_embeddings` 20행 / `github_repository` 30행 중 EMBEDDED 3행, Seq Scan(인덱스 없음) | **Execution Time 2.257ms**, Planning Time 1.492ms |
| pgvector 검색 (앱 측 `System.nanoTime()`, HikariCP 커넥션 포함) | 앱 재시작 후 첫 호출(콜드) | **3134ms** |
| pgvector 검색 (앱 측 `System.nanoTime()`, HikariCP 커넥션 포함) | 두 번째 호출부터(웜) | **185ms** |
| RAG 생성(LLM) | llama3.2:3b, num_predict=200, 워밍업 상태 | eval **91.07초**(153토큰, 1.68 tok/s) / 전체 **142.76초** |
| RAG 생성(LLM, 참고) | 위와 동일 설정, 다른 시각 재측정(모델 완전 워밍업) | 전체 **82.95초** (같은 stack 파라미터, 응답 길이는 매번 다름 — LLM 생성이 확률적) |
| 트렌드분석 캐시 미스 | `GET /api/trends/analysis?repoId=77`, 1차 호출(LLM 실제 호출) | **71.24초** |
| 트렌드분석 캐시 히트 | 동일 요청 2차 호출(Redis TTL 1시간 내) | **0.081초** (81ms) |

**캐싱 효과**: 71.24초 → 0.081초, 약 **880배** 개선.

## 1. pgvector 검색 단독 시간

### 앱 코드 계측

`RepoRecommendService.recommend()`에서 `findNearestEmbeddedByEmbedding()` 호출 앞뒤로
`System.nanoTime()`을 찍어 이 쿼리 하나만의 순수 소요시간(HikariCP 커넥션 획득 포함,
임베딩/생성 호출은 제외)을 측정하도록 계측을 추가했다(영구 반영, `RepoRecommendService.java`).

```
2026-08-16T18:21:47.633+09:00 INFO ... RepoRecommendService : pgvector 유사도 검색 소요시간: 3134ms (candidateLimit=10)   ← 앱 재시작 후 첫 호출
2026-08-16T18:23:33.376+09:00 INFO ... RepoRecommendService : pgvector 유사도 검색 소요시간: 185ms (candidateLimit=10)    ← 두 번째 호출
```

첫 호출과 두 번째 호출 사이의 큰 차이(3134ms → 185ms)는 HikariCP 커넥션 풀 워밍업 +
JIT/쿼리 플랜 캐싱 비용으로 판단된다 — 쿼리 자체의 실행 비용이 아니라는 건 아래
`EXPLAIN ANALYZE`로 별도 확인했다.

### `EXPLAIN ANALYZE` (기존 방식 재사용)

앱이 실제로 만든 쿼리 벡터(`search_query: Java,Spring Boot,Kafka`를 nomic-embed-text로
임베딩한 768차원 벡터)를 그대로 사용해 postgres Pod에서 직접 실행:

```sql
EXPLAIN ANALYZE
SELECT re.* FROM repo_embeddings re
JOIN github_repository gr ON gr.github_repository_id = re.repo_id
WHERE gr.process_status = 'EMBEDDED'
ORDER BY re.embedding <=> CAST('[...768차원...]' AS vector)
LIMIT 10;
```

```
 Limit  (cost=3.67..3.67 rows=2 width=58) (actual time=0.852..0.865 rows=3 loops=1)
   ->  Sort  (cost=3.67..3.67 rows=2 width=58) (actual time=0.849..0.858 rows=3 loops=1)
         Sort Method: quicksort  Memory: 25kB
         ->  Hash Join  (cost=2.41..3.66 rows=2 width=58) (actual time=0.579..0.700 rows=3 loops=1)
               Hash Cond: (re.repo_id = gr.github_repository_id)
               ->  Seq Scan on repo_embeddings re  (cost=0.00..1.18 rows=18 width=50) (actual time=0.024..0.100 rows=20 loops=1)
               ->  Hash  (cost=2.38..2.38 rows=3 width=8) (actual time=0.089..0.091 rows=3 loops=1)
                     ->  Seq Scan on github_repository gr  (cost=0.00..2.38 rows=3 width=8) (actual time=0.048..0.062 rows=3 loops=1)
                           Filter: ((process_status)::text = 'EMBEDDED'::text)
                           Rows Removed by Filter: 27
 Planning Time: 1.492 ms
 Execution Time: 2.257 ms
```

**실행 계획 확인 사항**:
- `repo_embeddings`(20행), `github_repository`(30행, 그중 EMBEDDED 3행) 둘 다
  **Seq Scan**(순차 스캔) — pgvector 인덱스(ivfflat/hnsw)가 없다. 지금 데이터 규모(20건)에서는
  옵티마이저가 인덱스보다 순차 스캔을 더 싸다고 판단하는 게 맞는 선택이지만, 데이터가
  수만~수십만 건으로 늘어나면 순차 스캔 비용이 선형으로 증가해 인덱스가 필요해질 것 —
  현재는 문제 없지만 트렌드 수집이 누적될수록 재검토 필요.
- 순수 DB 실행 시간은 **2.257ms**로 매우 빠르다. 앱 측에서 관측된 185ms(웜 상태)와의
  차이(~183ms)는 네트워크 왕복(로컬 port-forward) + JDBC 드라이버 + Hibernate 매핑
  오버헤드로 추정된다(이 부분은 "추정"이라고 명시 — 별도 실측 안 함).

## 2. 트렌드 분석 캐싱 히트/미스

`GET /api/trends/analysis?repoId=77`(codecrafters-io/build-your-own-x)를 Redis
`trendAnalysis` 캐시가 완전히 비어있는 상태(`redis-cli KEYS "*trendAnalysis*"` 확인 후
진행)에서 연속 2회 호출.

```
=== 1차 (캐시 미스, LLM 실제 호출) ===
HTTP 200, elapsed=71.241330s

=== 2차 (캐시 히트, Redis에서 바로 반환) ===
HTTP 200, elapsed=0.081030s
```

두 응답의 `analysis` 텍스트는 완전히 동일 — `@Cacheable(value = "trendAnalysis", key = "#repoId")`가
정상 동작해 LLM을 다시 호출하지 않고 캐시된 결과를 그대로 반환했음을 확인.

```
$ kubectl exec redis-... -- redis-cli TTL "trendAnalysis::77"
3584   ← 설정된 TTL(1시간=3600s) 대비 정상 범위
```

## 3. RAG 생성(LLM) — 이전 세션 실측치 재확인

`num_predict=200` 적용 커밋(`04d6ca1`) 당시 실측치를 표에 그대로 인용:

```
prompt eval time =  34669.46 ms /  320 tokens ( 9.23 tok/s)
eval time        =  91072.94 ms /  153 tokens ( 1.68 tok/s)
total time       = 125742.40 ms
truncated = 0   ← num_predict 한도 안에서 자연 종료(안 잘림)
```
클라이언트 측: `HTTP 200, elapsed=142.757719s`

같은 날 이번 세션에서 모델이 완전히 워밍업된 상태로 같은 `stack` 파라미터로 재호출한
결과도 참고용으로 기록: **82.95초** (200 OK). LLM 생성은 확률적이라 매번 응답 토큰 수가
달라(153개 vs 이번 호출 분량 상이) 직접 비교보다는 "워밍업 상태에서도 편차가 크다"는
참고 자료로만 사용한다.

## 인사이트

1. **pgvector 검색은 병목이 아니다.** DB 실행 시간 2.257ms는 RAG 전체 응답시간
   (수십~백여 초)에서 사실상 무시할 수 있는 수준. 병목은 전적으로 LLM 생성(CPU 추론)이다.
2. **캐싱이 실질적 효과가 있다.** 트렌드 분석 캐시 히트는 미스 대비 약 880배 빠르다 —
   이전에 정한 "읽기 빈도 높음 + 갱신 시점 예측 가능 + 지연 허용" 캐싱 3조건이
   TTL 1시간 캐싱으로 이 정도의 실질 효과를 낸다는 걸 이번에 처음 숫자로 확인했다.
3. **앱 측 커넥션 워밍업 비용이 생각보다 크다.** pgvector 쿼리 자체는 2ms대인데 앱
   재시작 후 첫 호출은 3134ms가 걸렸다 — HikariCP 초기 커넥션 생성 비용으로 추정.
   상시 운영 환경(앱을 계속 띄워두는 배포 환경)에서는 문제 안 되지만, 로컬 개발/재시작
   직후 첫 요청이 느린 이유를 설명할 근거가 된다.
