#!/usr/bin/env bash
# =============================================================================
# toss-poc-order.sh — 토스 "실주문" PoC (⚠️ 실계좌·실제 돈)
#
# 목적: P6b에서 확정한 주문 요청/응답/상세/취소·체결(execution) 매핑을 실계좌로 검증.
#       코드가 의존하는 필드(result.orderId, execution.filledQuantity 등)가 실제로 채워지는지.
#
# ★안전 설계:
#   - 정규장에만 시장가/소수 주문 접수됨(장외 422). 스크립트가 개장여부 확인·차단.
#   - clientOrderId(멱등성 키)로 재실행 시 중복주문 방지.
#   - PHASE 1(기본)은 "무체결": 시장가보다 낮은 지정가 매수→상세→취소(돈 안 나감).
#   - PHASE 2(실체결)는 명시 플래그 없으면 실행 안 함.
#
# 사용:
#   PHASE 1 (안전, 무체결):    bash toss-poc-order.sh limit-cancel [SYMBOL]
#   PHASE 2 (실체결, 극소액):  POC_CONFIRM='I-UNDERSTAND-REAL-MONEY' POC_USD=2 \
#                              bash toss-poc-order.sh market-roundtrip [SYMBOL]
#
# 키: 환경변수 TOSS_APP_KEY / TOSS_APP_SECRET (하드코딩 금지). TOSS_ACCOUNT(accountSeq) 선택.
#   서버에서: set -a; . ~/projects/.env; set +a; bash toss-poc-order.sh limit-cancel F
# =============================================================================
set -uo pipefail

MODE="${1:-limit-cancel}"
SYMBOL="${2:-F}"                       # 기본 F(Ford, 저가 유동주)
BASE_URL="${TOSS_BASE_URL:-https://openapi.tossinvest.com}"
T=12

[[ -z "${TOSS_APP_KEY:-}" || -z "${TOSS_APP_SECRET:-}" ]] && { echo "✋ TOSS_APP_KEY/SECRET 없음"; exit 1; }
command -v python3 >/dev/null || { echo "✋ python3 필요(JSON 파싱)"; exit 1; }
pp(){ python3 -m json.tool 2>/dev/null || cat; }
jget(){ python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]) if d else '')" "$1" 2>/dev/null; }
uuid(){ echo "poc-$(date +%Y%m%d%H%M%S)-$$-$RANDOM"; }
hr(){ printf '%.0s─' {1..70}; echo; }

# --- 토큰 ---
TOK=$(curl -s -m$T -X POST "$BASE_URL/oauth2/token" -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode grant_type=client_credentials --data-urlencode "client_id=$TOSS_APP_KEY" \
  --data-urlencode "client_secret=$TOSS_APP_SECRET" | jget ".get('access_token','')")
[[ -z "$TOK" ]] && { echo "✋ 토큰 실패(허용IP·자격 확인)"; exit 2; }
AUTH=(-H "Authorization: Bearer $TOK" -H "Accept: application/json")

# --- accountSeq ---
ACC="${TOSS_ACCOUNT:-}"
if [[ -z "$ACC" ]]; then
  ACC=$(curl -s -m$T "${AUTH[@]}" "$BASE_URL/api/v1/accounts" | jget "['result'][0]['accountSeq']")
fi
[[ -z "$ACC" ]] && { echo "✋ accountSeq 조회 실패"; exit 2; }
ACCH=(-H "X-Tossinvest-Account: $ACC")
JSON=(-H "Content-Type: application/json")

echo "토스 실주문 PoC  mode=$MODE symbol=$SYMBOL accountSeq=$ACC"
echo "현재 ET: $(TZ=America/New_York date '+%F %T %Z (%a)')"

# --- 개장 여부 확인 ---
TODAY=$(TZ=America/New_York date +%F)
CAL=$(curl -s -m$T "${AUTH[@]}" "${ACCH[@]}" "$BASE_URL/api/v1/market-calendar/US?from=$TODAY&to=$TODAY")
REG=$(printf '%s' "$CAL" | jget "['result']['today']['regularMarket']")
echo "오늘 정규장: ${REG:-없음(휴장)}"

# --- 예수금·현재가 ---
USD=$(curl -s -m$T "${AUTH[@]}" "${ACCH[@]}" "$BASE_URL/api/v1/buying-power?currency=USD" | jget "['result']['cashBuyingPower']")
LAST=$(curl -s -m$T "${AUTH[@]}" "${ACCH[@]}" "$BASE_URL/api/v1/prices?symbols=$SYMBOL" | jget "['result'][0]['lastPrice']")
echo "USD 예수금: \$$USD   $SYMBOL 현재가: \$$LAST"; hr

order_detail(){  # $1=orderId
  echo "▶ 주문상세 GET /api/v1/orders/$1"
  curl -s -m$T "${AUTH[@]}" "${ACCH[@]}" "$BASE_URL/api/v1/orders/$1" | pp; echo
}

case "$MODE" in
# ===========================================================================
limit-cancel)   # PHASE 1 — 무체결(안전): 낮은 지정가 매수 → 상세 → 취소
# ===========================================================================
  if [[ -z "$LAST" ]]; then echo "✋ 현재가 없음"; exit 3; fi
  # 시장가의 50% 지정가(체결 안 되게), 정수 1주, ≥$1이라 2자리
  PRICE=$(python3 -c "print(f'{float('$LAST')*0.5:.2f}')")
  CID=$(uuid)
  BODY=$(python3 -c "import json;print(json.dumps({'symbol':'$SYMBOL','side':'BUY','orderType':'LIMIT','quantity':'1','price':'$PRICE','timeInForce':'DAY','clientOrderId':'$CID'}))")
  echo "▶ [무체결] 지정가 매수 1주 @ \$$PRICE (시장가 \$$LAST의 50% — 체결 안 됨)"
  echo "  POST /api/v1/orders  body=$BODY"
  RESP=$(curl -s -m$T -X POST "${AUTH[@]}" "${ACCH[@]}" "${JSON[@]}" -d "$BODY" "$BASE_URL/api/v1/orders")
  echo "  ← $(printf '%s' "$RESP" | pp)"
  OID=$(printf '%s' "$RESP" | jget "['result']['orderId']")
  if [[ -z "$OID" ]]; then echo "✋ orderId 없음(위 응답 확인 — 장외/필드/권한). 주문 미생성."; exit 3; fi
  echo "  ✓ orderId=$OID"; hr
  sleep 1; order_detail "$OID"; hr
  echo "▶ 주문 취소 POST /api/v1/orders/$OID/cancel"
  curl -s -m$T -X POST "${AUTH[@]}" "${ACCH[@]}" "${JSON[@]}" "$BASE_URL/api/v1/orders/$OID/cancel" | pp; echo
  sleep 1; echo "▶ 취소 후 상세(상태 CANCELED 확인):"; order_detail "$OID"
  echo "✅ PHASE1 완료 — 주문 생성/상세/취소 배관 검증(무체결). 돈 안 나감."
  ;;

# ===========================================================================
market-roundtrip)  # PHASE 2 — 실체결(극소액): 시장가 소수 매수 → 상세 → 시장가 소수 매도 청산
# ===========================================================================
  if [[ "${POC_CONFIRM:-}" != "I-UNDERSTAND-REAL-MONEY" ]]; then
    echo "✋ PHASE2는 실제 체결(실제 돈)입니다. 실행하려면:"
    echo "   POC_CONFIRM='I-UNDERSTAND-REAL-MONEY' POC_USD=2 bash $0 market-roundtrip $SYMBOL"
    exit 4
  fi
  if [[ -z "$REG" || "$REG" == "None" ]]; then
    echo "✋ 정규장 아님 — 시장가/소수 주문은 장외에서 422. 정규장(월 KST22:30~) 재시도."; exit 4
  fi
  AMT="${POC_USD:-2}"
  CID=$(uuid)
  BUY=$(python3 -c "import json;print(json.dumps({'symbol':'$SYMBOL','side':'BUY','orderType':'MARKET','orderAmount':'$AMT','clientOrderId':'$CID'}))")
  echo "▶ [실체결] 시장가 소수 매수 \$$AMT of $SYMBOL"
  echo "  POST /api/v1/orders  body=$BUY"
  RESP=$(curl -s -m$T -X POST "${AUTH[@]}" "${ACCH[@]}" "${JSON[@]}" -d "$BUY" "$BASE_URL/api/v1/orders")
  echo "  ← $(printf '%s' "$RESP" | pp)"
  OID=$(printf '%s' "$RESP" | jget "['result']['orderId']")
  [[ -z "$OID" ]] && { echo "✋ 매수 orderId 없음(위 응답 확인). 청산 불필요."; exit 3; }
  echo "  ✓ 매수 orderId=$OID"; hr
  sleep 2; echo "▶ 매수 체결 상세(execution.filledQuantity/commission/tax 확인):"; order_detail "$OID"
  FILLED=$(curl -s -m$T "${AUTH[@]}" "${ACCH[@]}" "$BASE_URL/api/v1/orders/$OID" | jget "['result']['execution']['filledQuantity']")
  echo "  체결수량=$FILLED"; hr
  if [[ -z "$FILLED" || "$FILLED" == "0" || "$FILLED" == "None" ]]; then
    echo "⚠️ 체결수량 0/미확인 — 자동 매도 청산 생략. 수동 확인 필요."; exit 0
  fi
  CID2=$(uuid)
  SELL=$(python3 -c "import json;print(json.dumps({'symbol':'$SYMBOL','side':'SELL','orderType':'MARKET','quantity':'$FILLED','clientOrderId':'$CID2'}))")
  echo "▶ [청산] 시장가 소수 매도 $FILLED주 of $SYMBOL"
  echo "  POST /api/v1/orders  body=$SELL"
  RESP2=$(curl -s -m$T -X POST "${AUTH[@]}" "${ACCH[@]}" "${JSON[@]}" -d "$SELL" "$BASE_URL/api/v1/orders")
  echo "  ← $(printf '%s' "$RESP2" | pp)"
  OID2=$(printf '%s' "$RESP2" | jget "['result']['orderId']")
  [[ -n "$OID2" ]] && { sleep 2; echo "▶ 매도 체결 상세:"; order_detail "$OID2"; }
  echo "✅ PHASE2 완료 — 소수 시장가 매수·체결·매도청산 검증. 잔여 보유 확인:"
  curl -s -m$T "${AUTH[@]}" "${ACCH[@]}" "$BASE_URL/api/v1/holdings" | pp
  ;;
*)
  echo "✋ 알 수 없는 mode: $MODE (limit-cancel | market-roundtrip)"; exit 1;;
esac
