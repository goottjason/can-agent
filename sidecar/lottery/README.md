# 복권 구매 사이드카

Java(can-agent)가 subprocess로 호출한다. stdout 마지막 줄에 JSON을 출력한다.

## 설치
    python3 -m venv .venv && source .venv/bin/activate
    pip install -r requirements.txt
    playwright install chromium
    cp .env.example .env    # DH_USER / DH_PASSWORD 입력

## 사용
    python3 buy.py balance                         # 예치금만
    python3 buy.py buy --games LOTTO645,WIN720 --dry-run   # 구매 없이 로그인·잔액
    python3 buy.py buy --games LOTTO645,WIN720             # 실구매

## 연금(WIN720) 재시도 규약
- 한 번 실행에서 최대 `WIN720_ATTEMPTS`회(기본 3, .env로 조정) 시도. 실패 간격 5s→15s→30s.
- 확인창(alert) 문구를 캡처해 실패 사유에 남긴다(조용한 실패 방지).
- 완료 판정은 고정 대기가 아니라 최대 20초 폴링.
- **중복구매 차단**: BUY_NO·회차를 확보한 뒤 실패하면(=주문이 나갔을 수 있음) MY 당첨내역 원장에서
  해당 회차 연금 티켓을 확인한다. 이미 있으면 재구매하지 않고 성공으로 복구한다.
  원장 반영이 지연될 수 있으므로 **7초 간격 3회 재조회**(`ledger_has_ticket`) 후에만 '미구매'로 단정한다 —
  1회 조회로 단정하면 결제된 회차를 한 번 더 사게 된다.
- 예치금 부족·판매마감·한도 등은 하드스톱 — 재시도하지 않는다. 구매 전 예치금(1000원)도 선제 확인.

Java 쪽에서도 화·수·목 11/15시(KST)에 `buyWeekly()`를 재호출한다. 이번 주 미구매 게임만 요청하므로 멱등.

## 테스트
    .venv/bin/pip install pytest
    .venv/bin/python -m pytest test_buy.py -q     # 네트워크·브라우저 불필요

## Java 연동
application.yml:
    lottery.sidecar-command: "python3,/절대경로/sidecar/lottery/buy.py"
크리덴셜은 이 디렉터리 .env 에만 둔다(커밋 금지). Java는 크리덴셜을 다루지 않는다.
