# CodeScope 문서

| 문서 | 내용 |
|---|---|
| [troubleshooting.md](troubleshooting.md) | 문제 → 원인 → 해결 → 검증 기록. N+1, Flyway, Redis, OAuth/JWT, 배압, JFR pinning, Kafka, RAG, 그리고 2026-10-13 로컬 실측·Claude 전환에서 나온 14건 |
| [performance/llm-tx-boundary-and-embed-burst.md](performance/llm-tx-boundary-and-embed-burst.md) | 2026-10-13 실측: 커넥션 풀 고갈(OSIV) → load shedding(503) → 임베딩 버스트·DLT → 임베딩 입력 개선(MRR) |
| [performance/loadtest-guide.md](performance/loadtest-guide.md) | 부하테스트 절차: k6(open model) + Prometheus/Grafana + JFR + GC 로그, 비용 0 stub provider, 데이터 정리 |
| [performance/day35_backpressure.md](performance/day35_backpressure.md) | DB 세마포어 배압 실험(JMeter/k6, useSemaphore 토글) |
| [performance/day28_29_measurements.md](performance/day28_29_measurements.md) | pgvector 검색·LLM 생성·트렌드 캐시 실측(Ollama 시절 기준) |

## 도구

| 경로 | 용도 |
|---|---|
| `scripts/jfr-report.sh` | JFR 녹화에서 GC·할당·pinning·락 경합 리포트 |
| `scripts/loadtest-cleanup.sh` | stub 부하테스트가 남긴 트렌드 분석 데이터·캐시 정리(앱 종료 후) |
| `scripts/embedding-eval.py` | 임베딩 검색 품질 채점(MRR@10, Hit@3, Hub@3) — 임베딩 방식 변경 전후 비교 |
| `infra-monitoring/` | Grafana(:3001) · Prometheus(:9090) · InfluxDB(:8086), k6 시나리오 |
