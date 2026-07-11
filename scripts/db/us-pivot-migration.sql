-- 미국 대전환 P3 스키마 마이그레이션 (수동 ALTER)
-- 대상: prod DB `canagent` (postgres, projects-postgres-1).
-- 이유: ddl-auto=update 는 신규 컬럼만 추가하고 기존 컬럼의 타입/nullable 은 못 바꾼다.
--       아래 3개는 기존 컬럼 변경이라 수동 ALTER 필요.
-- 신규 컬럼(financial_statements.period_type/currency, stocks.cik/ticker/exchange/currency/sic_code)은
--   배포 시 Hibernate 가 자동 추가하므로 여기 없음.
-- 적용 시점: 배포 게이트(#9)에서 사용자 승인 후. 실행 전 백업 권장(financial_statements_bak_* 선례 존재).
-- 실행 예: docker exec -i projects-postgres-1 psql -U canagent -d canagent < us-pivot-migration.sql

BEGIN;

-- eps: 미국 EPS 소수 정밀 (numeric(19,2) → numeric(19,4))
ALTER TABLE financial_statements ALTER COLUMN eps TYPE numeric(19,4);

-- debt_ratio: 자본잠식·고부채 오버플로 방지 (numeric(5,2) → numeric(12,2))
ALTER TABLE financial_statements ALTER COLUMN debt_ratio TYPE numeric(12,2);

-- fiscal_quarter: 연간행(ANNUAL, 10-K) 수용 — NOT NULL 해제
ALTER TABLE financial_statements ALTER COLUMN fiscal_quarter DROP NOT NULL;

COMMIT;

-- ── 확인 쿼리 ─────────────────────────────────────────────
-- SELECT column_name, numeric_precision, numeric_scale, is_nullable
--   FROM information_schema.columns
--   WHERE table_name='financial_statements' AND column_name IN ('eps','debt_ratio','fiscal_quarter');

-- ── 국내 데이터 컷오버(배포 #9에서 결정, 여기 미포함) ────────
-- 현재 stocks 에 국내 종목 2763건(KOSPI/KOSDAQ/KONEX, active). 미국 전환 시:
--   UPDATE stocks SET active=false WHERE market IN ('KOSPI','KOSDAQ','KONEX');
-- (삭제 대신 비활성 권장 — portfolios/trades/financial_statements FK 히스토리 보존)
