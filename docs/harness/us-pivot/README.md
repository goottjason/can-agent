# 미국 대전환 — 설계·리서치 참조 (durable)

국내(KIS)→미국(토스 Open API + SEC EDGAR) 대전환의 사전조사·아키텍처 설계 문서. 원본은 `_workspace/`(gitignore)에 있으나, 머신 이식·이어작업을 위해 추적본을 여기 둔다. 단계별 진행 기록은 `docs/harness/working_history/2026-07-0*_미국대전환-*.md` 참조.

| 문서 | 내용 |
|---|---|
| [A](A_broker_fractional_research.md) | 국내 소수점 자동주문 API 부재 사전조사(브로커 비교) — 전환 동기 |
| [B](B_toss_us_pivot_duediligence.md) | 토스 US Open API 정밀 실사(주문·데이터·운영제약·재사용 판단) |
| [C](C_free_us_financial_data_research.md) | 무료 미국 재무데이터(SEC EDGAR 완전무료) — $0 파이프라인 |
| [D](D_kis_codebase_reuse_map.md) | KIS 코드베이스 재사용/폐기 맵(파일 단위) |
| [E](E_us_pivot_architecture_design.md) | **이관 아키텍처 설계** — 포트4개·어댑터·notional 사이징·ET시간·단계 P1~P9 |

## 진행 현황 (2026-07-10)
- **커밋 완료**: P1(포트 추출)·P2(가격 decimal)·P3(도메인 스키마)·P4(EDGAR 재무)·P5(토스 캔들)·P6a(토스 필드명 실스펙 교정). `us-pivot` 브랜치, 각 단계 clean test 그린(191).
- **대기 중**: P6 본체(TossBrokerAdapter + notional 사이징 = 중대등급). 토스 Open API **사전신청→순차개방 대기**(개인 자동매매가 공식 타깃, B2B 전용 아님). 키 발급 후 주문 바디 스키마 확정 필요.
- **KIS 원본**: `kis-main` 브랜치에 보존(origin push 완료).
- **다음**: 키/주문스키마 확보 시 P6b(브로커 골격+notional, 주문 바디 tunable) 구현 → P6c 실 모의 PoC. 상세는 E §7 P6·§8.
