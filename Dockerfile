# ──────────────────────────────────────
# Stage 1: builder
# 왜 JDK인가: 컴파일(gradlew build)에는 javac 등 빌드 도구가 필요해
#   JRE만으로는 불가능. 이 스테이지의 용량은 최종 이미지에 남지 않음
# ──────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Gradle 설정 파일만 먼저 복사해 의존성 레이어를 소스 코드 변경과 분리
# 왜: src/ 이하 코드만 바뀐 빌드에서는 이 레이어가 캐시로 재사용되어
#   매번 의존성을 다시 받지 않음
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src ./src

# 왜 -x test인가: 이 프로젝트의 상당수 테스트(@SpringBootTest, @DataJpaTest
#   replace=NONE)가 실제 PostgreSQL/Redis 연결을 필요로 하는데, 이미지 빌드
#   컨테이너 안에는 그런 인프라가 없어 애초에 통과할 수 없음. 테스트 자체는
#   CI 파이프라인(ci.yml의 test 잡)에서 이미 실행·검증되므로 이미지 빌드
#   단계에서 중복 실행할 필요가 없음
RUN ./gradlew build -x test --no-daemon

# Spring Boot Gradle 플러그인은 기본적으로 실행 가능한 fat jar(classifier
# 없음)와 의존성 없는 plain jar(classifier=plain) 둘 다 build/libs에 생성함.
# 다음 스테이지 COPY가 여러 파일에 매칭되면 대상이 모호해지므로,
# 여기서 미리 실행용 jar 하나만 골라 고정된 이름으로 확정
RUN cp $(find build/libs -name '*.jar' ! -name '*-plain.jar') app.jar

# ──────────────────────────────────────
# Stage 2: runner
# 왜 JRE인가: 컴파일러 등 빌드 도구가 포함된 JDK 대신, 실행에만 필요한
#   JRE로 최종 이미지를 구성해 이미지 크기를 줄임
# ──────────────────────────────────────
FROM eclipse-temurin:21-jre AS runner
WORKDIR /app

# 왜 non-root 사용자인가: 컨테이너가 root 권한으로 실행되면 컨테이너
# 탈출/침해 시 호스트에 더 큰 피해로 이어질 수 있어 최소 권한 원칙 적용
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

COPY --from=builder /app/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
