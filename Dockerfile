# build stage
#
# --platform=$BUILDPLATFORM : 빌더 스테이지를 "타깃"이 아니라 "러너의 네이티브 아키텍처"(amd64)에 고정한다.
#   JVM 산출물(jar)은 아키텍처 중립이므로 arm64 이미지를 만들 때도 교차 컴파일이 필요 없다.
#   이 지정이 없으면 arm64 타깃 빌드에서 Gradle·JVM 컴파일 전체가 QEMU 에뮬레이션으로 돌아 빌드 시간이 수십 분 단위로 늘어난다. 아래 run stage 만 타깃 아키텍처로 빌드된다.
FROM --platform=$BUILDPLATFORM gradle:8.6.0-jdk21 AS builder

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew

RUN ./gradlew dependencies --no-daemon

COPY src src

RUN ./gradlew bootJar --no-daemon

# Run Stage
# 가벼운 이미지로. 이 스테이지만 타깃 아키텍처(linux/arm64 · linux/amd64)로 빌드된다.
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar ./app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
