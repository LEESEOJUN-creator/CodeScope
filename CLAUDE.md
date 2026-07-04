# CodeScope CLAUDE.md

## 프로젝트 소개
GitHub 오픈소스 생태계를 실시간으로 분석하고,
"왜 이 기술이 뜨는가"와 "내 스택으로 기여할 수 있는 프로젝트"를
AI가 추천해주는 사용자 기반 플랫폼.

## 문제 정의
GitHub Trending은 순위만 보여줄 뿐,
왜 뜨는가, 내가 기여할 수 있는가,
내 스택과 맞는 프로젝트가 어디 있는가를 알 수 없다.
CodeScope는 실시간 수집 + AI 분석 + 벡터 유사도 검색 + 사용자 개인화로
이를 해결한다.

## 기술 스택
- Backend: Java 21 (가상 스레드), Spring Boot 3.5.16, Spring Data JPA
- Auth: Spring Security + OAuth2 Client (GitHub OAuth) + JWT
- Database: PostgreSQL + pgvector + Flyway
- Cache: Redis
- Message Queue: Kafka
- AI/LLM: Ollama (로컬) / OpenAI (배포)
- Infrastructure: kind (로컬) + k3s on Oracle Cloud (배포)
- CI/CD: GitHub Actions + ArgoCD GitOps

## 패키지 구조
com.codescope
├── api/controller           ← GithubRepositoryController 등
├── client/github             ← GitHub API 클라이언트 + OAuth (Day 6)
├── client/llm                ← Ollama/OpenAI 클라이언트 (Day 15)
├── common/entity              ← BaseEntity
├── common/exception           ← GlobalExceptionHandler
├── common/response            ← ApiResponse<T>
├── domain/repo/entity         ← GithubRepository(ProcessStatus 포함), Issue,
│                                 Topic, TrendScore, RepoEmbedding
├── domain/repo/repository     ← JpaRepository 인터페이스 5개
├── domain/repo/service        ← GithubRepositoryService
├── domain/repo/dto            ← Request/Response DTO
├── domain/user                ← User, UserFavorite, UserSkill 엔티티 뼈대
│                                 (entity/repository/service 세분화는
│                                  서비스 로직 구현 시점인 4주차에 진행)
├── infra/config               ← SwaggerConfig, SecurityConfig
└── kafka/producer,consumer    ← Kafka 파이프라인 (Day 11)

## ERD
GithubRepository - Topic         : ManyToMany (중간테이블 repo_topic)
GithubRepository - Issue         : OneToMany
GithubRepository - TrendScore    : OneToOne (TrendScore가 FK 보유)
GithubRepository - RepoEmbedding : OneToOne (RepoEmbedding이 FK 보유)
User - UserFavorite - GithubRepository : User(1)-UserFavorite(N)-GithubRepository(1)
[매핑 엔티티, 즐겨찾기 시각/알림설정 확장 대비]
User - UserSkill - Topic               : User(1)-UserSkill(N)-Topic(1)
[매핑 엔티티, 순수 ManyToMany 대신 채택]

## 코딩 컨벤션
- DTO는 record 사용 (불변, 보일러플레이트 제거)
- 응답은 ApiResponse<T> 래퍼 사용
- @Autowired 금지, 생성자 주입만 사용
- @Transactional(readOnly = true) 조회 메서드 기본 적용
- Setter 금지 → update() 비즈니스 메서드 패턴
- 정적 팩토리 메서드 사용 (of() / from()), 필드 많으면 @Builder(생성자에만 부착)
- 모든 연관관계 fetch = FetchType.LAZY 강제
- 단방향 연관관계 우선 (양방향 지양)
- Topic처럼 단순 연결만 필요한 N:M은 @ManyToMany 유지,
  User처럼 부가 메타데이터 확장 가능성이 높은 N:M은 매핑 엔티티로 설계
- @Enumerated(EnumType.STRING) 강제 (ORDINAL 금지)
- PK에 @Column(name = "테이블명_id") 명시
- FK는 @JoinColumn(name = "...") 명시
- 임계치/설정값은 application.yml로만 관리 (하드코딩 금지)
- 환경변수는 .env 또는 K8s Secret으로 관리 (OAuth client-id/secret 포함)
- Flyway 마이그레이션 파일은 한번 적용되면 수정 금지, 변경은 항상 새 버전 파일로

## 주요 설계 결정

### 데이터/스키마
- pgvector vs Pinecone: 별도 서버 없이 PostgreSQL 확장으로 벡터 검색,
  동일 트랜잭션 처리 가능. 현재 embeddingJson은 임시 TEXT
- Flyway 조기 도입 (2주차, 원래 계획은 4주차): 4주차 시점엔 이미 테이블 8개와
  실 데이터가 쌓여 있어 V1__init.sql을 역산하기 어려워짐. 2주차에
  ddl-auto: create-drop → validate로 전환해 스키마를 조기 캡처하고,
  4주차엔 V2__change_embedding_to_vector.sql만 추가
- GithubRepository.ProcessStatus(COLLECTED/EMBEDDED/FAILED): Kafka 파이프라인이
  Collect/Embed 두 단계로 나뉘어 있어, 중간 단계 실패 시 DB엔 있지만
  벡터는 없는 반쪽짜리 데이터(State Drift)가 생길 수 있음.
  RAG 추천 쿼리는 반드시 status=EMBEDDED만 필터링

### 동시성/가상 스레드
- 가상 스레드: I/O 대기 많은 구조(GitHub·AI·DB)에 적합.
  단, synchronized/구버전 JDBC의 pinning 이슈는 JFR(jdk.VirtualThreadPinned)로
  검증 필요, 하위 시스템 배압 제어 필수
- 세마포어 이원화: API 세마포어(GitHub용, OpenAI용 개별 Bean, rate limit이
  서로 달라 병목 구분 위해 분리)와 DB 세마포어(HikariCP maximum-pool-size와
  permits 수를 일치시켜 커넥션 풀 고갈 방지)를 분리 설계
- 톰캣 스레드 수 자체를 줄이는 것은 안티패턴: 인바운드 요청까지 막히고,
  가상 스레드의 "대기 시 캐리어 스레드 반납" 장점이 무력화됨

### 메시징
- Kafka vs @Async: @Async는 앱 재시작 시 작업 유실, Kafka는 브로커에 영속 저장
  + DLT 재처리 + Consumer 수평 확장
- Kafka Consumer 오프셋 역전 방지: 메시지 "소비" 자체는 순차 처리
  (concurrency는 파티션 단위로만 확장, Consumer 내부 가상 스레드 병렬화 금지).
  I/O 대기 지점(API 호출)에는 가상 스레드 사용 가능 — 소비 순서와
  I/O 처리 방식은 별개 문제. DB unique 제약(fullName)으로 멱등성 보장

### AI/RAG
- RAG: 단순 LLM 호출은 환각 발생, pgvector 검색 결과를 컨텍스트로 주입해 방지.
  LangChain 없이 직접 구현해 각 단계(Retrieval/Augmented/Generation) 통제

### 인증
- GitHub OAuth: 서비스 정체성과 일치, 로그인 시 사용자 GitHub 데이터
  활용 가능. 비밀번호 직접 관리 불필요 (보안 위험 위임)
- JWT vs 세션: 수평 확장(K8s) 지향이라 상태 없는 JWT 채택
- User-GithubRepository/Topic 관계: 순수 ManyToMany 대신
  UserFavorite/UserSkill 매핑 엔티티로 설계 (즐겨찾기 시각, 알림설정 같은
  부가 메타데이터 확장 대비). Topic-GithubRepository는 단순 태그 연결이라
  순수 ManyToMany 유지

### 구조
- 단방향 연관관계: 양방향은 JSON 직렬화/toString 무한루프,
  연관관계 주인 관리 실수 위험
- domain/repo 단일 Aggregate: Issue/Topic/TrendScore/RepoEmbedding이
  GithubRepository 없이 독립 존재 불가하므로 같은 Aggregate로 관리.
  User는 독립 도메인이라 domain/user로 분리하되, 현재는 엔티티 뼈대만
  두고 세부 계층(entity/repository/service) 분리는 실사용 시점에 진행

### 인프라
- kind + k3s: Docker Compose 대신 실제 K8s 경험 + Oracle Cloud Free Tier $0
- Oracle Cloud Idle VM 회수 정책 대응: Free Tier는 CPU/메모리 사용률이
  낮으면 휴면 계정으로 간주해 VM을 회수함. 스케줄러가 1시간에 한 번만
  짧게 돌아 평균 사용률이 낮을 위험이 있어, 최소 부하 유지 스크립트를
  배포 후 등록 (인스턴스 생성 실패 시 리전 재시도도 별개로 필요)

## 핵심 기능 (구현 순서)
1. 실시간 GitHub 트렌드 수집 (1시간마다 스케줄러)
2. AI 트렌드 분석 (왜 이 기술이 뜨는가)
3. 유사 프로젝트 추천 (pgvector 코사인 유사도 + RAG, ProcessStatus=EMBEDDED만 필터링)
4. 기여 이슈 추천 (good-first-issue + LLM 우선순위)
5. 기술 생태계 분석 (연관 기술 트렌드)
6. 사용자 개인화 (GitHub 로그인 기반 즐겨찾기/관심스택/맞춤추천, UserFavorite/UserSkill 서비스 로직)

## API 명세
[레포]
GET    /api/repos                            ← 전체 조회
GET    /api/repos/{id}                       ← 단건 조회
POST   /api/repos                            ← 생성
PUT    /api/repos/{id}                        ← 수정
DELETE /api/repos/{id}                       ← 삭제
GET    /api/repos/trending?period=WEEKLY&limit=20
[분석/추천]
GET    /api/trends/analysis?topic=kafka
GET    /api/recommend?skills=Spring Boot,Redis,Kafka  (비로그인)
GET    /api/recommend                        (로그인 시 저장된 스택 자동 사용)
GET    /api/issues/recommend?skills=Java,Spring Boot
GET    /api/ecosystem?tech=kafka
[사용자]
GET    /oauth2/authorization/github          ← GitHub 로그인 시작
GET    /api/users/me                         ← 내 정보 조회
POST   /api/users/me/bookmarks/{repoId}      ← 레포 즐겨찾기 (UserFavorite)
GET    /api/users/me/bookmarks               ← 즐겨찾기 목록

## Claude Code 사용 원칙
1. 내가 먼저 설계한다 → AI에게 구현 요청
2. AI가 짠 코드는 반드시 읽고 이해한다
3. 설명 못 하는 코드는 커밋하지 않는다
4. 슬래시 커맨드(/feature 등)는 관련 파일을 연쇄적으로 확장하는 경향이 있어,
   작은 단위 작업은 범위를 명시한 자연어 요청으로 진행하고
   요청하지 않은 파일은 건드리지 않는다