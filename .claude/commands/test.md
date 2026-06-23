현재 파일 또는 내가 지정한 파일의 테스트 코드를 작성해줘.

## 기본 원칙
- JUnit5 사용
- given-when-then 패턴으로 작성
- 테스트 메서드명은 한글로 작성 (무엇을_테스트하는지_명확하게)
- 설명 못 하는 테스트 코드는 작성하지 않는다

## 테스트 종류별 기준

1. Repository 테스트 (@DataJpaTest)
    - 저장 후 조회 확인
    - N+1 발생 여부 확인 (쿼리 카운트 검증)
    - 동적 검색 조건별 결과 확인

2. Service 테스트 (@ExtendWith(MockitoExtension.class))
    - 정상 케이스
    - 예외 케이스 (존재하지 않는 데이터, 중복 등)
    - Mock 객체로 외부 의존성 격리

3. Redis 테스트
    - setIfAbsent 중복 방지 동작 확인
    - Sorted Set 점수 갱신 확인
    - TTL 만료 확인

4. Kafka 테스트 (@EmbeddedKafka)
    - 메시지 발행 후 Consumer 수신 확인
    - Dead Letter Topic 전송 확인

## 주의사항
- 테스트끼리 독립적으로 실행 가능해야 함
- @Transactional로 테스트 후 DB 롤백
- 하드코딩된 테스트 데이터 금지 → 변수로 관리