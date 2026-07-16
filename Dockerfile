# 멀티스테이지: 서버가 소스에서 직접 부트 JAR을 빌드한다(sbshop식 CI/CD 대응).
# 기존 JAR-복사 방식(로컬 bootJar→scp)에서 전환 — git pull + docker compose up --build 만으로 배포.

# --- Build stage: 소스 → 부트 JAR ---
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY . .
# bootJar만 실행하면 plain jar(-plain.jar)는 생성되지 않아 build/libs에 부트 JAR 1개만 남는다.
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# --- Runtime stage ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 복권 사이드카 런타임. 로또: dhapi 순수 HTTP. 연금(WIN720): Playwright+Chromium
# (dhapi 세션쿠키 주입으로 OCR 불필요 — tesseract 미포함). Chromium 포함으로 이미지가 커진다.
COPY sidecar/lottery/ /app/sidecar/lottery/
# 한 RUN으로: apt update(목록 유지) → python venv → pip → playwright chromium(+OS deps).
# --with-deps 가 apt 목록을 필요로 하므로 update 를 먼저 하고 마지막에 목록 정리한다.
# ports.ubuntu.com(Canonical ARM 미러)이 이 서버에서 도달 불가 → 도달 가능한 ubuntu-ports 미러로 교체.
RUN sed -i 's|http://ports.ubuntu.com/ubuntu-ports/|https://free.nchc.org.tw/ubuntu-ports/|g' /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv \
    && python3 -m venv /app/sidecar/lottery/.venv \
    && /app/sidecar/lottery/.venv/bin/pip install --no-cache-dir -r /app/sidecar/lottery/requirements.txt \
    && /app/sidecar/lottery/.venv/bin/playwright install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
