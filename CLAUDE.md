# CodeScope CLAUDE.md

## 프로젝트 소개
GitHub 오픈소스 생태계를 실시간으로 분석하고,
"왜 이 기술이 뜨는가"와 "내 스택으로 기여할 수 있는 프로젝트"를
AI가 추천해주는 플랫폼.

## 문제 정의
GitHub Trending은 순위만 보여줄 뿐,
왜 뜨는가, 내가 기여할 수 있는가, 내 스택과 맞는 프로젝트가 어디 있는가를 알 수 없다.
CodeScope는 실시간 수집 + AI 분석 + 벡터 유사도 검색으로 이를 해결한다.

## 기술 스택
- Backend: Java 17, Spring Boot 3.5.15, Spring Data JPA
- Database: PostgreSQL + pgvector
- Cache: Redis
- Message Queue: Kafka
- AI/LLM: Ollama (로컬) / OpenAI (배포)
- Infrastructure: kind (로컬) + k3s on Oracle Cloud (배포)
- CI/CD: GitHub Actions + ArgoCD GitOps

## 패키지 구조
com.codescope
- domain/repo, topic, issue, trend, embedding
- api/repo, trend, recommend, issue
- kafka/producer, consumer
- client/github, llm
- infra/config
- common/response, exception

## 핵심 기능
1. 실시간 GitHub 트렌드 수집 (1시간마다 스케줄러)
2. AI 트렌드 분석 (왜 이 기술이 뜨는가)
3. 유사 프로젝트 추천 (pgvector 코사인 유사도 + RAG)
4. 기여 이슈 추천 (good-first-issue + LLM 우선순위)
5. 기술 생태계 분석 (연관 기술 트렌드)

## API 명세
GET  /api/repos/trending?period=WEEKLY&limit=20
GET  /api/trends/analysis?topic=kafka
GET  /api/recommend?skills=Spring Boot,Redis,Kafka
GET  /api/issues/recommend?skills=Java,Spring Boot
GET  /api/ecosystem?tech=kafka
GET    /api/repos
GET    /api/repos/{id}
POST   /api/repos
PUT    /api/repos/{id}
DELETE /api/repos/{id}

## ERD 요약
GithubRepo - Topic      : ManyToMany
GithubRepo - Issue      : OneToMany
GithubRepo - TrendScore : OneToMany
GithubRepo - RepoEmbedding : OneToOne

## 코딩 컨벤션
- DTO는 record 사용
- 응답은 ApiResponse<T> 래퍼 사용
- @Autowired 금지, 생성자 주입만 사용
- @Transactional(readOnly = true) 조회 메서드 기본 적용
- 임계치/설정값은 application.yml로만 관리 (하드코딩 금지)
- 환경변수는 .env 또는 K8s Secret으로 관리

## 주요 설계 결정
- Kafka vs @Async: 앱 재시작 시 @Async는 작업 유실, Kafka는 브로커에 영속 저장
- pgvector vs Pinecone: 별도 서버 없이 PostgreSQL 확장으로 벡터 검색 가능
- Redis Sorted Set: 실시간 랭킹을 DB ORDER BY 대신 O(log N)으로 처리
- RAG: 단순 LLM 호출은 환각 발생, pgvector 검색 결과를 컨텍스트로 주입해 방지
- kind + k3s: Docker Compose 대신 실제 K8s 경험 + Oracle Cloud Free Tier $0

## Claude Code 사용 원칙
1. 내가 먼저 설계한다 → AI에게 구현 요청
2. AI가 짠 코드는 반드시 읽고 이해한다
3. 설명 못 하는 코드는 커밋하지 않는다