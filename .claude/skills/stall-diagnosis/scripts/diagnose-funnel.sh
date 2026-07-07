#!/usr/bin/env bash
# CAN-Agent 매매 정지 진단 — 읽기 전용 일괄 조회.
# 서버 상태를 변경하는 명령은 절대 포함하지 않는다.
#
# 사용: bash diagnose-funnel.sh
# 환경변수(선택): SERVER_HOST(기본 168.107.31.154) SERVER_USER(기본 ubuntu)
#                SSH_KEY(기본 ./ssh-key-2026-06-25.key) APP_CONTAINER PG_CONTAINER DB_USER DB_NAME
set -u

HOST="${SERVER_HOST:-168.107.31.154}"
USER="${SERVER_USER:-ubuntu}"
KEY="${SSH_KEY:-./ssh-key-2026-06-25.key}"
DB_USER="${DB_USER:-canagent}"
DB_NAME="${DB_NAME:-canagent}"

SSH="ssh -i $KEY -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new $USER@$HOST"

if ! $SSH "echo ok" >/dev/null 2>&1; then
  echo "[FAIL] 서버 ssh 접속 불가 ($USER@$HOST, key=$KEY) — 정적 진단으로 전환하라." >&2
  exit 2
fi

APP="${APP_CONTAINER:-$($SSH "docker ps --format '{{.Names}}' | grep -i can-agent | head -1")}"
PG="${PG_CONTAINER:-$($SSH "docker ps --format '{{.Names}}' | grep -iE 'postgres|pg' | head -1")}"
echo "== 컨테이너: app=$APP pg=$PG"
$SSH "docker ps --format 'table {{.Names}}\t{{.Status}}'"

echo; echo "== G1. 스케줄러 가동 흔적 (최근 로그)"
$SSH "docker logs --tail 3000 $APP 2>&1 | grep -E '장중 모니터링|대상 종목 수|자동매매|스케줄' | tail -20"
echo "-- 에러/실패 패턴"
$SSH "docker logs --tail 3000 $APP 2>&1 | grep -E '토큰 발급 실패|잔고 조회 실패|주문 실패|예수금 부족|최대 보유|최소 주문금액|장 마감으로|ERROR' | tail -30"

PSQL="docker exec $PG psql -U $DB_USER -d $DB_NAME -tAc"

echo; echo "== G2. 종목/주가 데이터"
$SSH "$PSQL \"SELECT 'active stocks: '||COUNT(*) FROM stock WHERE active = true\""
$SSH "$PSQL \"SELECT 'price rows by date: '||date||' -> '||COUNT(*) FROM stock_price GROUP BY date ORDER BY date DESC LIMIT 5\"" 2>/dev/null \
  || $SSH "$PSQL \"SELECT 'price rows by date: '||date||' -> '||COUNT(*) FROM stock_prices GROUP BY date ORDER BY date DESC LIMIT 5\""

echo; echo "== G4. 데이터 완비율"
$SSH "$PSQL \"SELECT 'stocks with financials: '||COUNT(DISTINCT stock_id) FROM financial_statement\"" 2>/dev/null \
  || $SSH "$PSQL \"SELECT 'stocks with financials: '||COUNT(DISTINCT stock_id) FROM financial_statements\""
$SSH "$PSQL \"SELECT 'stocks with >=200d history: '||COUNT(*) FROM (SELECT stock_id FROM stock_price GROUP BY stock_id HAVING COUNT(*) >= 200) t\"" 2>/dev/null || true
$SSH "$PSQL \"SELECT 'stocks missing sector: '||COUNT(*) FROM stock WHERE active = true AND (sector IS NULL OR sector = '')\"" 2>/dev/null || true

echo; echo "== G5. 점수 분포 (최신 분석일)"
for T in analysis_score analysis_scores; do
  $SSH "$PSQL \"SELECT 'latest analysis: '||MAX(analysis_date)||' rows='||COUNT(*)||' max='||MAX(total_score)||' avg='||ROUND(AVG(total_score),1) FROM $T WHERE analysis_date = (SELECT MAX(analysis_date) FROM $T)\"" 2>/dev/null && break
done

echo; echo "== G6. 실행 조건"
$SSH "$PSQL \"SELECT 'active positions: '||COUNT(*) FROM portfolio WHERE active = true\"" 2>/dev/null || true
for T in trade trades; do
  $SSH "$PSQL \"SELECT 'trades last 7d: '||COUNT(*) FROM $T WHERE trade_date_time >= NOW() - INTERVAL '7 days'\"" 2>/dev/null && break
done

echo; echo "== 환경변수 존재 확인 (값 비출력)"
$SSH "docker exec $APP sh -c 'for v in KOREA_INVESTMENT_APP_KEY KOREA_INVESTMENT_APP_SECRET KOREA_INVESTMENT_ACCOUNT_NUMBER KOREA_INVESTMENT_IS_REAL REAL_TRADING DART_API_KEY KRX_API_KEY SPRING_PROFILES_ACTIVE; do eval val=\\\"\\\$\$v\\\"; if [ -n \"\$val\" ]; then echo \"\$v: set(len \${#val})\"; else echo \"\$v: MISSING\"; fi; done'" 2>/dev/null || echo "(컨테이너 env 확인 실패 — docker inspect로 대체 확인 필요)"

echo; echo "== 완료. 각 게이트 판정은 SKILL.md 절차에 따라 해석할 것."
