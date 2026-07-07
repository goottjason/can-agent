---
name: canagent-dev
description: "CAN-Agent(자동매매 시스템) 개발 오케스트레이터. 대시보드/모니터링 개선, 관측성(퍼널·탈락 사유) 구현, 점수·전략 로직 수정, 워커/API/알림 기능 추가, 배포 전 검증 등 이 프로젝트의 코드 변경 작업 전반에 반드시 사용. 후속 작업(다시 실행, 재실행, 수정, 보완, 업데이트, 이전 결과 개선, '대시보드만 다시', '백엔드만 다시')에도 반드시 사용. 순수 원인 진단만 필요하면 stall-diagnosis 스킬, 단순 질문은 직접 응답."
---

# CAN-Agent Dev Orchestrator

CAN-Agent의 개발 작업을 에이전트 팀으로 조율하는 통합 스킬. 이 시스템은 **실계좌 주문을 내는 매매 시스템**이므로 검증 없이 배포 경로 코드를 바꾸지 않는다.

## 실행 모드: 하이브리드

| Phase | 모드 | 이유 |
|-------|------|------|
| 진단 (필요 시) | 서브 에이전트 | trade-diagnostician 단독, 결과 파일만 반환 |
| 구현 | 에이전트 팀 | backend ↔ dashboard 간 API shape 실시간 조율 필요 |
| 검증 | 팀 내 점진 (incremental QA) | 각 모듈 완성 직후 qa-inspector 투입 |

## 에이전트 구성

| 팀원 | 정의 | 역할 | model | 출력 |
|------|------|------|-------|------|
| trade-diagnostician | `.claude/agents/trade-diagnostician.md` | 매매 정지/이상 진단 (stall-diagnosis 스킬 사용) | opus | `_workspace/{NN}_diagnostician_stall-report.md` |
| backend-builder | `.claude/agents/backend-builder.md` | Java/Spring 구현 | opus | `_workspace/{NN}_backend_changes.md` |
| dashboard-builder | `.claude/agents/dashboard-builder.md` | Thymeleaf/JS/CSS 구현 | opus | `_workspace/{NN}_dashboard_changes.md` |
| qa-inspector | `.claude/agents/qa-inspector.md` | 경계면 교차검증 + 테스트 실행 | sonnet | `_workspace/{NN}_qa_report.md` |

공용 참조(팀원 프롬프트에 실경로로 전달): `references/architecture-map.md`(구조·게이트), `references/dashboard-requirements.md`(R1~R5), `references/dev-rules.md`, `references/tdd-doctrine.md`.

## 리스크 등급 (게이트 강도)

| 등급 | 해당 작업 | 게이트 |
|------|----------|--------|
| 경량 | 문구·CSS·문서·설정 주석 | 내부 QA 생략 가능, `./gradlew test`만 |
| 표준 | 대시보드 기능, 관측성 추가, 리포지토리 쿼리 | incremental QA + 전체 테스트 |
| 중대 | **주문/매도 판단 로직, 포지션 사이징, 스키마 파괴적 변경, min-score 등 전략 임계값** | QA + 시뮬레이션 확인 + **사용자 승인 후 커밋·배포** |

외부 리뷰어(codex/agy) 미설치 환경 — 외부 리뷰 루프는 생략하고 내부 QA로 게이트한다. 도구 설치 시 `/myharness` 재실행으로 추가 가능.

## 워크플로우

### Phase 0: 컨텍스트 확인
1. `_workspace/` 존재 확인:
   - 미존재 → 초기 실행
   - 존재 + 부분 수정 요청 → 해당 팀원만 재호출 (이전 산출물 경로를 프롬프트에 포함)
   - 존재 + 새 과제 → 기존 `_workspace/`를 `_workspace_{YYYYMMDD_HHMMSS}/`로 이동 후 새로 생성
2. `docs/harness/working_history/`의 **최신 결과서**에서 `## 다음 단계 참조` 블록을 읽고 미해결·결정 사항을 이어받는다.
3. 요청이 "왜 안 되지" 계열이면 stall-diagnosis 스킬 경로인지 먼저 판단 — 진단만 필요하면 이 오케스트레이터 없이 trade-diagnostician 서브 1명으로 처리.

### Phase 1: 준비
1. 요청을 리스크 등급으로 판정하고 사용자에게 등급·계획을 한 줄로 알린다 (중대 등급이면 승인 관문 예고).
2. `_workspace/` 생성, 요구사항을 `_workspace/00_input.md`에 기록 (dashboard-requirements.md의 R번호로 매핑).

### Phase 2: 진단 (증상 기반 요청일 때만)
**모드: 서브.** `Agent(subagent_type: "trade-diagnostician", run_in_background: false)` — stall-diagnosis 절차 수행. 결과의 "코드 수정" 항목이 Phase 3의 입력.

### Phase 3: 구현
**모드: 팀.** 단일 메시지에서 병렬 spawn:
```
Agent(subagent_type: "backend-builder", prompt: "...작업 지시 + 참조 경로...")
Agent(subagent_type: "dashboard-builder", prompt: "...")
Agent(subagent_type: "qa-inspector", prompt: "...대기 후 모듈 단위 검증...")
```
`TaskCreate`로 작업 등록 — dashboard 작업은 backend의 API shape 산출에 `depends_on`. 통신 규칙:
- backend → dashboard: API shape 변경 시 즉시 SendMessage
- 빌더 → qa: 모듈 완성 시 검증 요청 (incremental QA — 전체 완성 대기 금지)
- qa → 빌더 양쪽: 경계면 이슈는 양측 모두에게 통지

### Phase 4: 최종 검증 게이트
1. qa-inspector 최종 리포트에서 차단 항목 0 확인 + `./gradlew test` 전체 통과.
2. 실패 시 해당 빌더 재작업 → 재검증 (수정분만).

### Phase 5: 결과서·커밋·정리
1. 결과서 1장(T1)을 `docs/harness/working_history/{YYYY-MM-DD}_{과제명}.md`로 작성 — 변경 요약, 검증 결과, **`## 다음 단계 참조`**(미해결·핵심 결정과 이유·다음 단계) 필수. 자기완결로 작성(_workspace 없어도 재구성 가능하게).
2. **커밋 순서**: QA PASS → 승인 관문 → 단일 커밋. 승인 관문 기본은 사용자 승인 대기. `_workspace/.autonomous` 마커 또는 "자율로"/"승인 생략" 발화 시 자동 통과(중대 등급은 예외 — 항상 사용자 승인).
3. **배포(`deploy.sh`)와 push는 자율 모드여도 항상 사용자 승인** — 실서버·실계좌 영향.
4. 팀원 shutdown 요청, `_workspace/` 보존, 사용자에게 요약 보고 + 개선 피드백 기회 제공.

## 데이터 흐름

```
[리더] → (필요시) diagnostician(서브) → stall-report.md
       → backend-builder ←SendMessage(shape)→ dashboard-builder
              │(모듈 완성시)                      │(모듈 완성시)
              └────────→ qa-inspector ←──────────┘
                              ↓
                    qa_report.md (PASS) → 결과서 → 승인 → 커밋
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| 팀원 실패/중지 | SendMessage로 상태 확인 → 재시작 1회 → 실패 시 리더가 직접 수행하거나 범위 축소 후 보고서에 명시 |
| 테스트 회귀 | 원인 빌더 재작업. 2회 실패 시 변경 롤백(비파괴 — stash/revert) 후 사용자 보고 |
| backend·dashboard shape 충돌 | backend의 `*_backend_changes.md` 명세를 정본으로 판정, 양쪽 통지 |
| 작업 상태 지연 | 상태 표시만 신뢰하지 않고 SendMessage로 완료 보고 요구 → 리더가 TaskUpdate |
| 서버 접근 불가(진단) | 정적 진단으로 강등, 미확인 게이트 명시 |

## 테스트 시나리오

**정상**: "대시보드에 하트비트와 Top-20 리더보드 넣어줘" → Phase 0(초기) → 표준 등급 → backend(쿼리+status 확장) ∥ dashboard(R1·R2 화면) → incremental QA → 전체 테스트 통과 → 결과서 → 승인 → 커밋.

**에러**: dashboard-builder가 backend 미완성 필드를 참조 → qa가 경계면 불일치 검출(템플릿 `${...}` vs Model 속성) → 양측 통지 → backend shape 확정 후 dashboard 수정 → 재검증 통과. 최종 보고서에 재작업 이력 기록.

**진단 분기**: "오늘도 매매가 없네?" → Phase 2만 실행(서브 1명) → 원인이 설정(min-score)이면 코드 변경 없이 보고로 종료 — 게이트 생략(skip-when-no-delta).
