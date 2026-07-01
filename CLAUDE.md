# CodeScope CLAUDE.md

## 프로젝트 소개
GitHub 오픈소스 생태계를 실시간으로 분석하고,
"왜 이 기술이 뜨는가"와 "내 스택으로 기여할 수 있는 프로젝트"를
AI가 추천해주는 플랫폼.

## 문제 정의
GitHub Trending은 순위만 보여줄 뿐,
왜 뜨는가, 내가 기여할 수 있는가,
내 스택과 맞는 프로젝트가 어디 있는가를 알 수 없다.
CodeScope는 실시간 수집 + AI 분석 + 벡터 유사도 검색으로 이를 해결한다.

## 기술 스택
- Backend: Java 21, Spring Boot 3.5.16, Spring Data JPA
- Database: PostgreSQL + pgvector
- Cache: Redis
- Message Queue: Kafka
- AI/LLM: Ollama (로컬) / OpenAI (배포)
- Infrastructure: kind (로컬) + k3s on Oracle Cloud (배포)
- CI/CD: GitHub Actions + ArgoCD GitOps

## 패키지 구조
com.codescope
├── api/controller/repo      ← Controller (GithubRepositoryController)
├── client/github            ← GitHub API 클라이언트 (Day 6)
├── client/llm               ← Ollama/OpenAI 클라이언트 (Day 15)
├── common/entity            ← BaseEntity
├── common/exception         ← GlobalExceptionHandler
├── common/response          ← ApiResponse<T>
├── domain/repo/entity       ← GithubRepository, Issue, Topic,
│                               TrendScore, RepoEmbedding
├── domain/repo/repository   ← JpaRepository 인터페이스 5개
├── domain/repo/service      ← GithubRepositoryService
├── domain/repo/dto          ← Request/Response DTO
├── infra/config             ← SwaggerConfig
└── kafka/producer,consumer  ← Kafka 파이프라인 (Day 11)

## ERD
GithubRepository - Topic        : ManyToMany (중간테이블 repo_topic)
GithubRepository - Issue        : OneToMany
GithubRepository - TrendScore   : OneToOne (TrendScore가 FK 보유)
GithubRepository - RepoEmbedding: OneToOne (RepoEmbedding이 FK 보유)

## 코딩 컨벤션
- DTO는 record 사용 (불변, 보일러플레이트 제거)
- 응답은 ApiResponse<T> 래퍼 사용
- @Autowired 금지, 생성자 주입만 사용
- @Transactional(readOnly = true) 조회 메서드 기본 적용
- Setter 금지 → update() 비즈니스 메서드 패턴
- 정적 팩토리 메서드 사용 (of() / from())
- @Builder는 생성자에만 부착 (클래스 레벨 금지)
- 모든 연관관계 fetch = FetchType.LAZY 강제
- 단방향 연관관계 우선 (양방향 지양)
- @Enumerated(EnumType.STRING) 강제 (ORDINAL 금지)
- PK에 @Column(name = "테이블명_id") 명시
- FK는 @JoinColumn(name = "...") 명시
- 임계치/설정값은 application.yml로만 관리 (하드코딩 금지)
- 환경변수는 .env 또는 K8s Secret으로 관리

## 주요 설계 결정
- Kafka vs @Async: 앱 재시작 시 @Async는 작업 유실,
  Kafka는 브로커에 영속 저장
- pgvector vs Pinecone: 별도 서버 없이 PostgreSQL 확장으로
  벡터 검색 가능, 동일 트랜잭션 처리 가능
- Redis Sorted Set: 실시간 랭킹을 DB ORDER BY 대신
  O(log N)으로 처리
- RAG: 단순 LLM 호출은 환각 발생,
  pgvector 검색 결과를 컨텍스트로 주입해 방지
- kind + k3s: Docker Compose 대신 실제 K8s 경험 +
  Oracle Cloud Free Tier $0
- 단방향 연관관계: 양방향은 JSON 직렬화 무한루프,
  toString 무한루프, 연관관계 주인 관리 실수 위험
- domain/repo 단일 Aggregate: Issue/Topic/TrendScore/RepoEmbedding이
  GithubRepository 없이 독립 존재 불가하므로 같은 Aggregate로 관리

## 핵심 기능 (구현 순서)
1. 실시간 GitHub 트렌드 수집 (1시간마다 스케줄러)
2. AI 트렌드 분석 (왜 이 기술이 뜨는가)
3. 유사 프로젝트 추천 (pgvector 코사인 유사도 + RAG)
4. 기여 이슈 추천 (good-first-issue + LLM 우선순위)
5. 기술 생태계 분석 (연관 기술 트렌드)

## API 명세
GET  /api/repos                              ← 전체 조회
GET  /api/repos/{id}                         ← 단건 조회
POST /api/repos                              ← 생성
PUT  /api/repos/{id}                         ← 수정
DELETE /api/repos/{id}                       ← 삭제
GET  /api/repos/trending?period=WEEKLY&limit=20
GET  /api/trends/analysis?topic=kafka
GET  /api/recommend?skills=Spring Boot,Redis,Kafka
GET  /api/issues/recommend?skills=Java,Spring Boot
GET  /api/ecosystem?tech=kafka

## Claude Code 사용 원칙
1. 내가 먼저 설계한다 → AI에게 구현 요청
2. AI가 짠 코드는 반드시 읽고 이해한다
3. 설명 못 하는 코드는 커밋하지 않는다
