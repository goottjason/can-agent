# 멀티스테이지: 서버가 소스에서 직접 부트 JAR을 빌드한다(sbshop식 CI/CD 대응).
# 기존 JAR-복사 방식(로컬 bootJar→scp)에서 전환 — git pull + docker compose up --build 만으로 배포.

# --- Build stage: 소스 → 부트 JAR ---
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
# bootJar만 실행하면 plain jar(-plain.jar)는 생성되지 않아 build/libs에 부트 JAR 1개만 남는다.
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# --- Runtime stage ---
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
