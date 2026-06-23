새 기능을 구현할 때 아래 순서대로 진행해줘.
내가 먼저 설계를 설명할게. 설명 듣고 나서 구현 시작해.

## 구현 순서
1. Entity (domain 패키지)
    - @Entity, @Id, @GeneratedValue
    - 연관관계 설정 (ManyToMany, OneToMany, OneToOne)
    - LAZY 로딩 기본 적용
    - Lombok @Getter, @NoArgsConstructor(access = PROTECTED)

2. Repository (domain 패키지)
    - JpaRepository 상속
    - 필요한 쿼리 메서드 추가
    - QueryDSL이 필요한 경우 별도 QueryRepository 작성

3. DTO (api 패키지)
    - Request: record로 작성 + @Valid 검증
    - Response: record로 작성
    - Entity 직접 반환 금지

4. Service (api 패키지)
    - @Transactional(readOnly = true) 기본 적용
    - 쓰기 작업만 @Transactional
    - 외부 API 호출은 @Transactional 범위 밖에서 실행

5. Controller (api 패키지)
    - @RestController
    - ApiResponse<T> 래퍼로 응답
    - @Valid로 입력값 검증

6. 예외 처리
    - CustomException 사용
    - GlobalExceptionHandler에 등록

## 컨벤션
- 생성자 주입만 사용 (@Autowired 금지)
- 하드코딩 금지 (application.yml로 관리)
- 설명 못 하는 코드는 작성하지 않는다

## 구현 전 확인
- 내가 설계를 설명했는가?
- 패키지 위치가 구조에 맞는가?
- 테스트 코드도 함께 작성할 것인가?