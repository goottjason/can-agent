#!/usr/bin/env bash
# =============================================================================
# toss-poc-readonly.sh — 토스 Open API "읽기전용" PoC (미국 대전환)
#
# 목적: 실제 토스 응답의 JSON 필드명을 눈으로 확인해, 코드에 박아둔
#       "=== PoC 미확정 ===" 추정 상수(예: assets의 availableCash, 환율 rate,
#       계좌 헤더 X-Tossinvest-Account)를 실제값과 대조하기 위함.
#
# ★안전: 조회(GET)와 토큰 발급(POST /oauth2/token)만 호출한다.
#         주문/정정/취소 등 상태변경 엔드포인트는 이 스크립트에 없다 → 돈 안 나감.
#
# 키 주입: 환경변수로만 읽는다(하드코딩 금지).
#   TOSS_APP_KEY     = Client Id     (tsck_live_...)
#   TOSS_APP_SECRET  = Client Secret (tssk_live_...)
#   TOSS_ACCOUNT     = (선택) 계좌 식별자. 있으면 X-Tossinvest-Account 헤더로 시험.
#   TOSS_BASE_URL    = (선택) 기본 https://openapi.tossinvest.com
#
# 실행 예 (서버에서, .env에 키가 있을 때):
#   set -a; . ~/projects/.env; set +a      # .env의 TOSS_* 를 환경으로 로드
#   bash scripts/toss-poc-readonly.sh
# 또는 인라인:
#   TOSS_APP_KEY=... TOSS_APP_SECRET=... bash scripts/toss-poc-readonly.sh
# =============================================================================
set -uo pipefail

BASE_URL="${TOSS_BASE_URL:-https://openapi.tossinvest.com}"
TIMEOUT=10

# --- 필수 키 확인 ---------------------------------------------------------
if [[ -z "${TOSS_APP_KEY:-}" || -z "${TOSS_APP_SECRET:-}" ]]; then
  echo "✋ TOSS_APP_KEY / TOSS_APP_SECRET 환경변수가 없습니다." >&2
  echo "   예) set -a; . ~/projects/.env; set +a; bash $0" >&2
  exit 1
fi

# --- JSON 예쁘게 출력 헬퍼(python3 있으면 정렬, 없으면 원본) ---------------
pretty() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -m json.tool 2>/dev/null || cat
  elif command -v jq >/dev/null 2>&1; then
    jq . 2>/dev/null || cat
  else
    cat
  fi
}

mask() {  # 토큰 등 민감값 일부만 노출
  local s="$1"
  local n=${#s}
  if (( n <= 12 )); then echo "***"; else echo "${s:0:6}...${s: -4} (len=$n)"; fi
}

hr() { printf '%.0s─' {1..70}; echo; }

# --- GET 호출 + 상태코드·본문 출력 헬퍼 -----------------------------------
# 사용: call_get "<라벨>" "<url>" [추가헤더...]
call_get() {
  local label="$1"; local url="$2"; shift 2
  hr
  echo "▶ ${label}"
  echo "  GET ${url}"
  local extra=()
  local h
  for h in "$@"; do extra+=(-H "$h"); done
  # -w 로 http 코드 분리, 본문은 그대로
  local resp code body
  resp=$(curl -s -m "$TIMEOUT" -w $'\n__HTTP__%{http_code}' \
              -H "Authorization: Bearer ${ACCESS_TOKEN}" \
              -H "Accept: application/json" \
              "${extra[@]}" \
              "$url" 2>&1) || true
  code="${resp##*__HTTP__}"
  body="${resp%$'\n'__HTTP__*}"
  echo "  ← HTTP ${code}"
  echo "$body" | pretty
  echo
}

echo "토스 읽기전용 PoC"
echo "  base-url : ${BASE_URL}"
echo "  app-key  : $(mask "${TOSS_APP_KEY}")"
echo "  account  : ${TOSS_ACCOUNT:-(미설정)}"

# =============================================================================
# 1) OAuth2 토큰 발급 — POST /oauth2/token (form-urlencoded, client_credentials)
# =============================================================================
hr
echo "▶ [1] 토큰 발급  POST ${BASE_URL}/oauth2/token"
TOKEN_RESP=$(curl -s -m "$TIMEOUT" -w $'\n__HTTP__%{http_code}' \
  -X POST "${BASE_URL}/oauth2/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Accept: application/json" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "client_id=${TOSS_APP_KEY}" \
  --data-urlencode "client_secret=${TOSS_APP_SECRET}" 2>&1) || true
TOKEN_CODE="${TOKEN_RESP##*__HTTP__}"
TOKEN_BODY="${TOKEN_RESP%$'\n'__HTTP__*}"
echo "  ← HTTP ${TOKEN_CODE}"
echo "$TOKEN_BODY" | pretty
echo
echo "  ※ 확인 포인트: 실제 토큰 필드명(access_token / token_type / expires_in)이 맞는지."

# access_token 추출(python3 우선, 실패 시 grep 폴백)
ACCESS_TOKEN=""
if command -v python3 >/dev/null 2>&1; then
  ACCESS_TOKEN=$(printf '%s' "$TOKEN_BODY" | python3 -c \
    'import sys,json;
try:
    print(json.load(sys.stdin).get("access_token",""))
except Exception:
    print("")' 2>/dev/null)
fi
if [[ -z "$ACCESS_TOKEN" ]]; then
  ACCESS_TOKEN=$(printf '%s' "$TOKEN_BODY" | grep -oE '"access_token"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"access_token"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')
fi

if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "✋ access_token 을 못 얻었습니다(위 응답 확인 — 필드명/자격증명/허용IP 점검)." >&2
  echo "   허용 IP에 이 서버 egress IP가 등록됐는지도 확인하세요." >&2
  exit 2
fi
echo "  ✓ access_token 획득: $(mask "$ACCESS_TOKEN")"
echo

# 계좌 헤더(있으면 시험) — assets/accounts에서 계좌 지정 방식 확인용
ACC_HEADER=()
if [[ -n "${TOSS_ACCOUNT:-}" ]]; then
  ACC_HEADER=("X-Tossinvest-Account: ${TOSS_ACCOUNT}")
fi

# =============================================================================
# 2~5) 읽기전용 조회들 — 실제 필드명 확인 대상
# =============================================================================
call_get "[2] 계좌 목록 (accountSeq 확인)  GET /api/v1/accounts" \
         "${BASE_URL}/api/v1/accounts" "${ACC_HEADER[@]}"

call_get "[3] 보유/잔고 (★USD 현금 필드명·holdings 구조)  GET /api/v1/assets" \
         "${BASE_URL}/api/v1/assets" "${ACC_HEADER[@]}"

call_get "[4] 환율 (rate 필드명)  GET /api/v1/exchange-rate" \
         "${BASE_URL}/api/v1/exchange-rate"

call_get "[5] 일봉 캔들 샘플 (envelope·timestamp 타입)  GET /api/v1/candles?symbol=AAPL&interval=1d&count=3" \
         "${BASE_URL}/api/v1/candles?symbol=AAPL&interval=1d&count=3"

hr
echo "완료. 위 [2]~[5] 응답의 실제 필드명을 코드 상수와 대조하세요."
echo "확인 대상 상수: assets availableCash/totalEvaluationAmount/holdings[...],"
echo "               exchange-rate rate, 계좌 지정 방식(헤더 vs accountSeq), candle envelope/timestamp."
echo "★ 이 스크립트는 주문을 내지 않습니다(읽기전용)."
