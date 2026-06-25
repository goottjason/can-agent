# can-agent 배포 아키텍처 완전 분석

## 전체 구조도

```
┌─────────────────────────────────────────────────────────────┐
│                     로컬 PC (Mac)                           │
│                                                             │
│  IntelliJ IDEA / VS Code                                   │
│  └── can-agent/                                            │
│      ├── src/          (소스 코드)                          │
│      ├── Dockerfile    (도커 이미지 빌드 규칙)              │
│      ├── deploy.sh     (수동 배포 스크립트)                 │
│      └── build/libs/   (빌드된 JAR 파일)                   │
│                                                             │
│  $ git push origin main  ──────────────────────────────┐   │
│  $ ./deploy.sh (수동 배포) ──────────────────────┐     │   │
└──────────────────────────────────────────────────│─────│───┘
                                                   │     │
                                                   │     │
┌──────────────────────────────────────────────────│─────│───┐
│                   GitHub                         │     │   │
│  goottjason/can-agent                            │     │   │
│  └── main 브랜치                                 │     │   │
│      ├── 소스 코드 (동기화)                       │     │   │
│      └── Webhook 설정                            │     │   │
│          └── push 이벤트 발생 시                  │     │   │
│              POST http://168.107.31.154:9000 ────│─────│───┘
│                                                 │     │
└─────────────────────────────────────────────────│─────│────┘
                                                  │     │
┌─────────────────────────────────────────────────│─────│────┐
│              Oracle Cloud ARM Server            │     │    │
│              IP: 168.107.31.154                 │     │    │
│                                                 │     │    │
│  ┌──────────────────────────────────────────────│─────│┐   │
│  │  Webhook Server (Python, 포트 9000)          │     ││   │
│  │  └── POST 요청 수신 → deploy.sh 실행         │◀────┘│   │
│  │      └── git pull → docker compose rebuild   │      │   │
│  └──────────────────────┬───────────────────────┘      │   │
│                         │                               │   │
│  ┌──────────────────────▼───────────────────────┐      │   │
│  │  Docker Engine (도커 런타임)                  │      │   │
│  │                                              │      │   │
│  │  ┌─────────┐ ┌──────────┐ ┌──────────────┐  │      │   │
│  │  │  nginx  │ │ postgres │ │  can-agent   │  │      │   │
│  │  │  :80    │ │  :5432   │ │    :8080     │  │      │   │
│  │  │ 리버스  │ │  DB 서버  │ │ Spring Boot  │  │      │   │
│  │  │ 프록시  │ │          │ │  웹 애플리케이션│  │      │   │
│  │  └────┬────┘ └──────────┘ └──────────────┘  │      │   │
│  │       │                                      │      │   │
│  │       ▼                                      │      │   │
│  │  /can-agent → can-agent:8080                 │      │   │
│  └──────────────────────────────────────────────┘      │   │
└────────────────────────────────────────────────────────┘   │
                                                              │
         사용자 �라우저                                       │
         http://168.107.31.154/can-agent ◀───────────────────┘
```

---

## 핵심 개념 사전 학습

### 1. 서버(Server)란?

> **서버 = 인터넷에 연결된 컴퓨터**

집에 있는 PC와 같습니다. 다만:
- **24시간 켜져 있습니다** (전원, 인터넷 끊기지 않는 한)
- **공인 IP**가 있어서 어디서든 접근할 수 있습니다
- **SSH(Secure Shell)**로 원격 제어합니다

우리의 Oracle Cloud ARM 서버:
| 항목 | 값 |
|------|-----|
| IP 주소 | `168.107.31.154` (공인 IP) |
| OS | Ubuntu 24.04 ARM64 |
| 하드웨어 | 4 CPU, 24GB RAM |
| 월 비용 | $0 (Always Free) |

### 2. SSH(Secure Shell)란?

> **다른 컴퓨터를 원격으로 제어하는 암호화된 연결**

```
내 PC (로컬) ──── SSH ────▶ 서버 (원격)
   │                           │
   │  ssh -i 키파일 ubuntu@IP  │
   │  ─────────────────────▶   │
   │                           │
   │  ◀── 명령어 결과 전송 ──  │
```

**SSH 키 기반 인증:**
- 공개키(Public Key): 서버에 설치 → `~/.ssh/authorized_keys`
- 프라이빗키(Private Key): 내 PC에 보관 → `ssh-key-2026-06-25.key`
- 이 키 쌍이 일치하면 로그인 허용
- 비밀번호 없이 안전하게 접속

**명령어:**
```bash
ssh -i /path/to/key.pem ubuntu@168.107.31.154
# -i: identity 파일(프라이빗키) 지정
# ubuntu: 로그인 사용자
# 168.107.31.154: 서버 IP
```

### 3. Docker란?

> **앱과 그 모든 의존성을 하나의 패키지로 묶는 기술**

```
傳統적 배포:                    Docker 배포:
┌──────────────┐              ┌──────────────────┐
│ 서버에        │              │ 컨테이너 1        │
│ JDK 설치     │              │ ┌──────────────┐ │
│ DB 설치      │              │ │ JDK + 앱 + 설정│ │
│ 설정 파일    │              │ │ (격적된 환경) │ │
│ 의존성 충돌  │              │ └──────────────┘ │
│ "내 PC에서는  │              ├──────────────────┤
│  되는데..."  │              │ 컨테이너 2        │
└──────────────┘              │ ┌──────────────┐ │
                              │ │ nginx + 설정  │ │
                              │ └──────────────┘ │
                              └──────────────────┘
```

**핵심 개념 3가지:**

| 개념 | 설명 | 비유 |
|------|------|------|
| **이미지(Image)** | 앱 실행에 필요한 모든 것 (코드, 런타임, 설정) | 레시피 |
| **컨테이너(Container)** | 이미지를 실제로 실행한 것 | 요리된 음식 |
| **Dockerfile** | 이미지를 만드는 단계별 지시문 | 요리 방법 |

**Dockerfile 예시 (can-agent):**
```dockerfile
# 베이스 이미지: Java 17 (ARM64)
FROM eclipse-temurin:17-jre-arm64

# 작업 디렉토리 생성
WORKDIR /app

# 빌드된 JAR 파일 복사
COPY build/libs/can-agent-0.1.0.jar app.jar

# 포트 노출 (문서화용, 실제로는 docker-compose에서 매핑)
EXPOSE 8080

# 앱 실행 명령어
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 4. Docker Compose란?

> **여러 컨테이너를 한 번에 관리하는 도구**

```yaml
# docker-compose.yml
services:
  nginx:       # 컨테이너 1: 리버스 프록시
    image: nginx:alpine
    ports:
      - "80:80"      # 외부 포트 80 → 컨테이너 포트 80
    volumes:
      - ./nginx/conf.d:/etc/nginx/conf.d  # 설정 파일 연결

  postgres:    # 컨테이너 2: 데이터베이스
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: canagent
      POSTGRES_USER: canagent
      POSTGRES_PASSWORD: canagent1234
    volumes:
      - pgdata:/var/lib/postgresql/data  # 데이터 영구 저장

  can-agent:   # 컨테이너 3: Spring Boot 앱
    build: ./can-agent  # Dockerfile 위치
    environment:
      SPRING_PROFILES_ACTIVE: prod
    depends_on:
      - postgres        # postgres 먼저 시작

volumes:
  pgdata:       # 데이터 볼륨 정의
```

**실행 명령어:**
```bash
docker compose up -d          # 백그라운드에서 모든 컨테이너 시작
docker compose up -d --build  # 이미지 재빌드 후 시작
docker compose down           # 모든 컨테이너 중지
docker compose ps             # 실행 중인 컨테이너 확인
```

### 5. Nginx 리버스 프록시란?

> **들어오는 요청을 적절한 컨테이너로 전달하는 중계 서버**

```
사용자 → http://168.107.31.154/can-agent
              │
              ▼
        ┌─────────────┐
        │   Nginx     │  :80 포트에서 대기
        │  (중계 서버) │
        └──────┬──────┘
               │
               │  /can-agent 경로 요청 감지
               │  → 내부 네트워크의 can-agent:8080으로 전달
               │
               ▼
        ┌──────────────┐
        │  can-agent   │  :8080 포트에서 대기
        │ (Spring Boot)│
        └──────────────┘
```

**Nginx 설정:**
```nginx
# /etc/nginx/conf.d/default.conf

# PostgreSQL 연결용 (다른 프로젝트에서 사용)
server {
    listen 80;
    server_name _;

    # can-agent 경로 처리
    location /can-agent {
        proxy_pass http://can-agent:8080;  # 내부 컨테이너로 전달
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

**왜 리버스 프록시를 쓰나?**
- 여러 앱을 하나의 IP(포트 80)로 접근 가능
- `/can-agent` → can-agent 앱
- `/sbshop` → sbshop-agent 앱 (추후)
- SSL 인증서도 한 곳에서 관리

### 6. 포트(Port)란?

> **컴퓨터 내부에서 프로그램을 구분하는 번호**

```
서버 (IP: 168.107.31.154)
├── 포트 22   → SSH (원격 접속)
├── 포트 80   → HTTP (웹사이트)
├── 포트 443  → HTTPS (보안 웹사이트)
├── 포트 5432 → PostgreSQL (데이터베이스)
├── 포트 8080 → can-agent (Spring Boot)
├── 포트 9000 → 웹훅 서버
└── ...
```

**포트 바인딩:**
```
0.0.0.0:80 → 포트 80에서 들어오는 모든 연결 허용
127.0.0.1:80 → 로컬에서만 접근 가능 (외부 접근 차단)
```

### 7. OCI 보안 규칙(Security List)이란?

> **오라클 클라우드 수준에서 포트를 여닫는 방화벽**

```
인터넷
  │
  ▼
┌─────────────────────────────────┐
│ OCI 보안 규칙 (Security List)    │  ← 여기서 걸러짐
│                                 │
│  포트 22  : 허용 ✅              │
│  포트 80  : 허용 ✅              │
│  포트 443 : 허용 ✅              │
│  포트 9000: 허용 ✅              │
│  그 외    : 차단 ❌              │
└─────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────┐
│ 서버 내부 iptables (OS 방화벽)    │  ← 두 번째 관문
│                                 │
│  포트 22  : 허용 ✅              │
│  포트 9000: 허용 ✅ (추가 필요)   │
│  그 외    : 차단 ❌              │
└─────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────┐
│ Docker 포트 매핑                 │  ← 세 번째 관문
│                                 │
│  80:80   → nginx 컨테이너        │
│  5432:5432 → postgres 컨테이너   │
└─────────────────────────────────┘
```

**이중 방화벽 개념:**
- OCI Security List: 클라우드 플랫폼 수준
- iptables/ufw: 운영체제 수준
- **둘 다 열어야** 외부에서 접근 가능

### 8. iptables란?

> **리눅스 커널 수준의 방화벽**

```bash
# 현재 규칙 확인
sudo iptables -L INPUT -n --line-numbers

# 결과:
# num  target  prot  source        destination
# 1    ACCEPT  all   0.0.0.0/0     0.0.0.0/0     state RELATED,ESTABLISHED
# 2    ACCEPT  icmp  0.0.0.0/0     0.0.0.0/0
# 3    ACCEPT  all   0.0.0.0/0     0.0.0.0/0
# 4    ACCEPT  tcp   0.0.0.0/0     0.0.0.0/0     tcp dpt:22
# 5    REJECT  all   0.0.0.0/0     0.0.0.0/0     reject-with icmp-host-prohibited
```

**규칙 설명:**
- 1번: 이미 열려 있는 연결 허용
- 2번: ICMP (핑) 허용
- 3번: Docker 관련 허용
- 4번: SSH (포트 22) 허용
- 5번: **나머지 모두 차단**

**포트 추가:**
```bash
# 포트 9000 허용 규칙 추가
sudo iptables -I INPUT 4 -p tcp --dport 9000 -j ACCEPT

# 영구 저장 (재부팅 후 유지)
sudo netfilter-persistent save
```

### 9. 웹훅(Webhook)이란?

> **이벤트 발생 시 다른 서버로 자동으로 데이터를 보내는 메커니즘**

```
[일반적인 HTTP 요청]           [웹훅]
클라이언트 → 서버              서버 A → 서버 B (이벤트 발생 시)
(내가 요청)                    (자동으로 알림)

예: "데이터 줘"                예: "푸시했다 알려줘"
```

**GitHub 웹훅 동작 흐름:**
```
1. 개발자가 git push 함
   $ git push origin main
        │
        ▼
2. GitHub가 웹훅 URL로 POST 요청 전송
   POST http://168.107.31.154:9000
   Header: X-GitHub-Event: push
   Body: { "ref": "refs/heads/main", "commits": [...] }
        │
        ▼
3. 서버의 웹훅 수신기가 요청 처리
   deploy.sh 실행:
   - git pull origin main (최신 코드 받기)
   - docker compose up -d --build (이미지 재빌드, 컨테이너 재시작)
        │
        ▼
4. 배포 완료! 사용자에게 최신 버전 서빙
```

### 10. Spring Boot 프로파일이란?

> **환경별로 다른 설정을 적용하는 메커니즘**

```yaml
# application.yml (공통 설정)
server:
  port: 8080

spring:
  jpa:
    hibernate:
      ddl-auto: update

---
# application-dev.yml (개발 환경)
spring:
  config:
    activate:
      on-profile: dev
  datasource:
    url: jdbc:postgresql://localhost:5432/canagent_dev

---
# application-prod.yml (운영 환경)
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    url: jdbc:postgresql://postgres:5432/canagent
```

**Docker에서 프로파일 선택:**
```yaml
environment:
  SPRING_PROFILES_ACTIVE: prod  # prod 프로파일 사용
```

**ddl-auto 옵션:**
| 값 | 설명 | 용도 |
|----|------|------|
| `none` | 아무것도 안 함 | 프로덕션 (가장 안전) |
| `validate` | 테이블 존재 여부만 확인 | 프로덕션 |
| `update` | 테이블 자동 생성/업데이트 | 개발/첫 배포 |
| `create` | 매번 테이블 삭제 후 재생성 | 테스트 |
| `create-drop` | 시작 시 생성, 종료 시 삭제 | 테스트 |

---

## 전체 배포 흐름 (6단계)

### Step 1: 코드 수정 (로컬 PC)

```
developer → IntelliJ에서 코드 수정 → git commit
```

### Step 2: GitHub에 푸시

```bash
$ git add .
$ git commit -m "기능 추가"
$ git push origin main
# → 로컬 브랜치 변경사항을 GitHub 서버에 전송
```

### Step 3: GitHub이 서버에 웹훅 전송

```
GitHub 서버 → POST http://168.107.31.154:9000
             Header: X-GitHub-Event: push
             Body: { "ref": "refs/heads/main", ... }
```

### Step 4: 서버에서 자동 배포

```bash
# webhook/deploy.sh 실행
cd /home/ubuntu/projects/can-agent
git pull origin main           # 최신 코드 받기
cd /home/ubuntu/projects
docker compose up -d --build can-agent  # 이미지 재빌드
```

### Step 5: Docker 이미지 재빌드

```
Dockerfile 읽기 → JAR 파일을 이미지에 복사 → 새 이미지 생성
→ 기존 컨테이너 중지 → 새 컨테이너로 교체 → 앱 재시작
```

### Step 6: 사용자에게 서빙

```
사용자 → http://168.107.31.154/can-agent
         → Nginx (포트 80) 수신
         → 내부 네트워크로 can-agent:8080에 전달
         → Spring Boot 응답 반환
```

---

## 현재 실행 중인 컨테이너

| 컨테이너 | 역할 | 포트 | 이미지 |
|----------|------|------|--------|
| `projects-nginx-1` | 리버스 프록시 | 80, 443 | nginx:alpine |
| `projects-postgres-1` | PostgreSQL DB | 5432 | postgres:16-alpine |
| `projects-can-agent-1` | Spring Boot 앱 | 8080 | eclipse-temurin:17-jre |

**컨테이너 확인 명령어:**
```bash
# 실행 중인 컨테이너 목록
docker ps

# 특정 컨테이너 로그 확인
docker logs projects-can-agent-1 --tail 50

# 컨테이너 내부 셸 접속
docker exec -it projects-can-agent-1 /bin/bash

# 컨테이너 리소스 사용량
docker stats
```

---

## 접속 및 확인 방법

### 웹 브라우저
```
http://168.107.31.154/can-agent  → 200 OK (앱 정상)
http://168.107.31.154            → Nginx 기본 페이지
```

### SSH 접속
```bash
ssh -i /Users/jason/IdeaProjects/can-agent/ssh-key-2026-06-25.key ubuntu@168.107.31.154
```

### 서버 상태 확인
```bash
# Docker 컨테이너 상태
docker ps

# can-agent 로그
docker logs projects-can-agent-1 -f  # -f: 실시간 로그

# 디스크 사용량
df -h

# 메모리 사용량
free -h

# CPU 사용량
top -bn1 | head -5
```

### 수동 배포 (로컬에서)
```bash
cd /Users/jason/IdeaProjects/can-agent
./deploy.sh
```

---

## 문제 해결 가이드

### 502 Bad Gateway
```
원인: Nginx가 백엔드 앱에 접근할 수 없음
해결: docker logs projects-can-agent-1 확인
      → 앱이 아직 시작 중이거나 에러 발생
```

### Connection Refused
```
원인: 포트가 열려 있지 않음
해결: OCI 보안 규칙 + iptables 둘 다 확인
      → sudo iptables -L INPUT -n
```

### Schema Validation Error
```
원인: ddl-auto: validate인데 테이블이 없음
해결: application-prod.yml에서 ddl-auto: update로 변경
```

### Cron Expression Error
```
원인: Spring Cron은 6개 필드 필요 (초 분 시 일 월 요일)
해결: "0 0 10 1,4,7,10 *" → "0 0 10 1,4,7,10 * *"
```

---

## 다음 단계 (추후 구현)

1. **도메인 연결**: Cloudflare Tunnel로 HTTPS + 도메인
2. **sbshop-agent 배포**: 두 번째 프로젝트 추가
3. **React 앱 배포**: 프론트엔드 앱들
4. **PostgreSQL 스키마 분리**: 프로젝트별 DB 격적
5. **GitHub Actions**: CI/CD 파이프라인
6. **모니터링**: Prometheus + Grafana
7. **아이들 방지**: cron으로 CPU 사용량 유지

---

## 유용한 명령어 모음

```bash
# === Docker ===
docker ps                              # 실행 중인 컨테이너
docker compose up -d --build           # 재빌드 후 시작
docker compose down                    # 모든 컨테이너 중지
docker logs <컨테이너명> --tail 50      # 로그 확인
docker exec -it <컨테이너명> /bin/bash  # 셸 접속
docker system prune -a                 # 사용하지 않는 이미지 정리

# === Git ===
git status                             # 변경사항 확인
git log --oneline -5                   # 최근 커밋 5개
git diff                               # 변경된 내용 보기

# === 서버 관리 ===
sudo systemctl status canagent-webhook # 웹훅 서비스 상태
sudo journalctl -u canagent-webhook -f # 웹훅 로그
sudo iptables -L INPUT -n              # 방화벽 규칙
sudo netfilter-persistent save         # 방화벽 규칙 영구 저장

# === 네트워크 테스트 ===
curl -v http://168.107.31.154/can-agent  # 상세 접속 테스트
curl -s -o /dev/null -w "%{http_code}" http://168.107.31.154/can-agent  # 상태코드만
```
