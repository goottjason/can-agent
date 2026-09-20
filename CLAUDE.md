# CAN-Agent

## 하네스: CAN-Agent 자동매매 시스템 개발

**목표:** 실계좌 매매 시스템의 안전한 기능 개발·관측성 개선·매매 정지 진단.

**트리거:**
- 코드 변경 작업(대시보드·관측성·전략·워커·API·알림 등) 요청 시 `canagent-dev` 스킬을 사용하라. 후속 수정·재실행·보완 요청도 동일.
- "매매가 안 된다 / 신호가 없다 / 왜 멈췄지" 등 원인 규명만 필요하면 `stall-diagnosis` 스킬을 사용하라.
- 단순 질문은 직접 응답 가능. 프로젝트 구조·게이트 사슬 파악은 `.claude/skills/canagent-dev/references/architecture-map.md` 참조.

**안전 규칙:** 매수/매도 판단·주문·포지션 사이징·전략 임계값 변경과 배포(`deploy.sh`)·push는 반드시 사용자 승인 후 진행 (중대 등급).

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-07-07 | 초기 구성 (에이전트 4 + 스킬 2 + 참조 4) | 전체 | 매매 정지 진단·대시보드 명확화 요구 |
| 2026-07-07 | 첫 실행: 서버 진단 → 매매 재개 수정 + 대시보드 관측성(R1~R4) 구현·배포 | 하네스 팀 전체 | 매매 0건 원인 확정(후보0+LazyInit) 및 해소 |
| 2026-07-07 | 대시보드 UI 리팩토링(상단 깨짐·버튼 툴팁/재배치·소개 5종·종목관리) + stale 수치 정합화 | dashboard-builder+qa | 스타일 깨짐·문서 부정확 지적 |
| 2026-07-09 | 매수 3문제: 주문 tr_id R→U(EGW00202 해소)·대시보드 잔고 견고화 배포 / 소액계좌 분산 정수매수 사이징(배포 대기) / 국내 소수점 API 불가 리서치 | backend+qa | 실계좌 매수 개시 후 주문실패·계좌표시·사이징 노출 |
| 2026-07-11 | 미국대전환 P6(us-pivot): 토스 주문 어댑터 + BrokerPort broker-중립 재설계 + notional 사이징 + MarketCalendarPort. QA PASS(208테스트), 미배포(토스 미승인·PoC 상수 대기) | backend+qa | 토스 승인 가정, 주문 경로 실장·픽스처 검증 |
| 2026-07-11 | 미국대전환 P7(us-pivot): 시간·스케줄 ET 재배선(존 Asia/Seoul→America/New_York, isTradingHours 3중복→MarketHours 공유유틸, 정적 NYSE 휴일폴백, 크론 3원 ET 정합, 템플릿 KST→ET). QA PASS(222테스트) | backend+dashboard+qa | 미국장 개장·크론을 ET로, 휴일 오주문 방지 |
| 2026-07-11 | 미국대전환 P8(us-pivot): 표시층 USD 통화·벤더(토스/SEC EDGAR)·market enum(NYSE/NASDAQ)·사이징 서사 정합 + **broker 토스 전환**(TossBrokerAdapter @Primary, prod는 꺼둠). QA PASS(224테스트), 미배포 | backend+dashboard+qa | 화면·주문경로를 미국(토스)으로, 실배포는 토스 키·PoC·승인 게이트 |
| 2026-07-11 | 미국대전환 P9(us-pivot): KRX/DART/Kakao 데드코드·6 DTO·corp_code_map·레거시 동기화 엔드포인트 제거 + 폐기 설정키(dart/krx/kakao) 정리 + 벤더명 rename(brokerPort/financialsPort). KIS 폴백 빈·설정 의도적 보존(§6 이탈). QA PASS(208테스트). **P1~P9 완료** | backend+dashboard+qa | 국내 잔재 정리, us-pivot 전환 완결 |
| 2026-07-11 | 미국대전환 P6b(us-pivot): 토스 실키 수령→읽기전용 PoC로 실 API 확정→어댑터 교정. result 언랩(캔들 0건 버그), 잔고 buying-power+holdings, 현재가/prices, 환율 파라미터, 소수매도 MARKET, clientOrderId 멱등성, OrderStatus FILLED, execution 매핑. 확정본 docs/us-pivot/F. QA PASS(216테스트). 실주문 미활성(환전·승인 전) | backend+qa | 추정 상수를 실 API로 확정, 조용한 실패 제거 |
| 2026-07-11 | 코드완성(us-pivot): market-calendar/US 실연동(정적 휴일폴백→실 토스 캘린더, API실패시 폴백)·잔재정리(ApiConfig KRX/DART 데드 제거·대시보드 E2E 어서션). QA PASS(217). CI/CD 인프라 선구축(자동배포 비활성)·출시 체크리스트 docs/us-pivot/CHECKLIST | backend+qa | 개장판정 실데이터화, 배포 준비 착수 |
| 2026-07-11 | 배포준비(us-pivot): DB 마이그레이션 SQL(eps 19,4·debt_ratio 12,2·fiscal_quarter nullable, scripts/db)·미국 유니버스 100종목(company_tickers.json, SEC 실CIK NYSE64/NASDAQ36)+`POST /stocks/load-universe`+버튼. GOOG/GOOGL 이중상장 가드. QA PASS(219). 적용은 배포 게이트 | backend+dashboard+qa | 스키마·유니버스 준비, 국내 2763종목 컷오버는 배포시 |
| 2026-09-04 | 연금복권 구매실패 재시도: 사이드카 3회 재시도+원장 7s×3 재조회 중복결제 가드·확인창 문구 캡처(사유 유실 제거)·완료판정 20s 폴링 / Java 재시도 크론(화수목 11·15시)+최종통보(목 15:30)+알림 주1회 억제 / 사이드카 출력읽기 스레드분리(스케줄러 스레드 영구점유 수정). QA PASS(270+pytest23), 미배포 | backend+qa | 330회 연금 미결제, 실패사유 유실·재시도 부재 |
| 2026-09-20 | 배포 표준화(sbshop-agent 와 동일 방식): Actions `validate→tests∥images→deploy` — 테스트 통과 후에만, GitHub arm64 러너가 이미지를 빌드해 GHCR(`ghcr.io/goottjason/can-agent:<SHA12>`)에 올리고 서버는 pull 만. 서버 `ops/deploy.sh`: 공용 잠금·정상 종료(SIGTERM 30초)·이미지 내용 지문으로 무변경 시 재시작 안 함·prev 롤백 태그·nginx 경유 라우팅 점검·자동 롤백(코드 7/8)·`ops/guard.sh` 가드 훅. 수동 `deploy.sh` 는 워크플로 실행 래퍼로 교체, compose 기본 비밀번호 폴백 제거 | ops/, .github/workflows/, deploy.sh, docker-compose.yml, .dockerignore | 서버에서 직접 `git pull`+`compose up` 하던 무방비 배포(잠금·롤백·헬스 없음, 정비 스크립트와 경합, 서버 CPU 빌드)를 실계좌 서버에 맞게 안전화 |
