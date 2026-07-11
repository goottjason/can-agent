# 미국 대전환 — 출시 체크리스트

CAN-Agent를 실계좌 미국 매매로 전환하기까지 남은 일. (2026-07-11 기준, 세션 Task와 동기)
상태: ⬜ 대기 · 🔄 진행중 · ✅ 완료 · ⛔ 게이트대기

현황 요약: 설계·구현(P1~P9)·PoC 실확정(P6b) 완료. 토스 실키 수령·환전(USD $77.54) 완료.
CI/CD 인프라 선구축(자동배포 비활성). 로컬 커밋 미푸시. 실서버 미배포(국내 코드 가동 중).

---

## 지금 할 수 있는 것 (완성 작업)

- [x] **#1 서버 전제 확인 + 멀티스테이지 Docker 빌드 시험** ✅
  - ✅ 멀티스테이지 Dockerfile 로컬 빌드 성공(546MB, arm64=서버 aarch64)
  - ✅ 서버는 git 체크아웃(main, origin보다 뒤=clean ancestor → pull=fast-forward)
  - ✅ compose can-agent 서비스, build:./can-agent (멀티스테이지와 호환)
  - ⚠️ **선결과제(아래 "Task #1 발견")**: 서버 미커밋 변경·compose TOSS env 누락

- [ ] **#2 DB 마이그레이션 SQL 준비 (P3 스키마)**
  - eps scale 2→4, debt_ratio precision 상향, periodType(QUARTER/ANNUAL)·fiscalQuarter nullable
  - 프로덕션 적용용 ALTER 스크립트(적용은 배포 게이트)

- [ ] **#3 미국 유니버스 적재 + 규모 결정**
  - company_tickers.json → Stock DB(SecTickerUniverseLoader), edgar.user-agent 필요
  - rate limit 고려 상위 N부터, exchange/cik 채워지는지 확인

- [ ] **#4 prod 설정 정리 (application-prod.yml + 서버 .env)**
  - toss.*·edgar.user-agent·telegram. broker.enabled는 PoC 후 true
  - 서버 .env에 TOSS_*/TELEGRAM_* 반영

- [ ] **#5 market-calendar/US 실연동 (정적 폴백 대체)**
  - TossMarketCalendarAdapter가 GET /api/v1/market-calendar/US 우선 사용(실패 시 정적 폴백)
  - 픽스처·테스트, canagent-dev 팀 경유

- [ ] **#6 잔재 정리 (선택)**
  - ApiConfig KRX/DART 중첩 프로퍼티 제거, DashboardE2ETest 본문 어서션($/NYSE 존재·원/KOSPI 부재)

## 월요일 장중 (시장 필요)

- [ ] **#7 소액 실주문 PoC 실행** ⛔(2026-07-13 월 정규장 KST 22:30~, 승인 필요)
  - scripts/toss-poc-order.sh: Phase1 limit-cancel → Phase2 market-roundtrip $2
  - 확인: 소수주문 최소금액·자릿수·정규장 접수·execution 실채움

## 배포 게이트 (준비 완료 후)

- [ ] **#8 CI/CD 자동배포 활성화** ⛔(← #1)
  - GitHub secret CANAGENT_DEPLOY_KEY 등록, deploy.yml push 트리거 해제

- [ ] **#9 실서버 배포 + 검증** ⛔(← #2·#3·#4·#7, 중대·승인 필수)
  - prod toss.broker.enabled=true, 마이그레이션 적용, 유니버스·재무·시세 적재
  - 배포→헬스체크→모니터링→소액 실매매 관측, 롤백(KIS 폴백/이전 커밋) 준비

- [ ] **#10 로컬 커밋 origin/main 푸시** (자동배포 없음 — GitHub 반영만)
  - 2ef43e9(PoC스크립트)·d2bad43(P6b)·18f9d32(CI/CD)·f224e12(실주문PoC) 등

---

## Task #1 발견 — git-pull CI/CD 배포 선결과제
서버(OCI ARM, aarch64)에 repo에 없는 배포 적응이 직접 들어가 있어, 지금 상태로는 `git pull` 자동배포가 안 된다:
1. **서버 working tree 미커밋 변경**: `Dockerfile`·`.dockerignore`·`.gitignore`·`build.gradle`(+dotenv-java) → `git pull` 충돌. 배포 전 정리(화해) 필요. → #8
2. **compose environment:에 TOSS_* 없음**: `~/projects/docker-compose.yml`의 can-agent 서비스가 TOSS 키를 컨테이너에 전달 안 함 → 배포된 US 앱이 토스 인증 실패. TOSS_APP_KEY/SECRET/ACCOUNT·TOSS_BROKER_ENABLED 추가 필요. → #4
3. **서버 .env에 TOSS_* 없음**(TELEGRAM_*·DART_API_KEY는 있음). → #4
4. dotenv-java는 우리 코드가 안 씀(System.getenv+Spring) → 무해하나 pull 충돌만 해소하면 됨.
- ✅ 로컬 빌드 성공으로 Dockerfile 자체는 정상. 남은 건 "서버 배포 상태를 repo와 화해"하는 1회 작업.

## 참고 사실 (PoC 확정)
- 계좌 accountSeq=1(accountNo 10701020640), 계좌헤더 X-Tossinvest-Account=accountSeq
- USD 예수금 $77.54(환전분), 미국 정규장 = KST 22:30~익일 05:00 (ET 09:30~16:00)
- 실 API 스펙: docs/harness/us-pivot/F_toss_poc_confirmed_spec.md
- 배포 방식: 서버가 git pull 후 소스 Docker 빌드(deploy.sh/워크플로), 서버 168.107.31.154
