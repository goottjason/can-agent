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
- **번호 소진 대비 재선택**: 연금복권720+는 조·번호별 발행량이 한정돼 자동으로 뽑힌 번호를
  못 살 수 있다. 번호가 확정되지 않으면 브라우저를 다시 띄우지 않고 **같은 페이지에서 다른 조로
  최대 3회 재선택**한다(조는 매번 섞어서 뽑는다).
- 확인창(alert) 문구를 캡처해 실패 사유에 남긴다(조용한 실패 방지).
- 완료 판정은 고정 대기가 아니라 최대 20초 폴링.
- **중복구매 차단**: BUY_NO·회차를 확보한 뒤 실패하면(=주문이 나갔을 수 있음) MY 당첨내역 원장에서
  해당 회차 연금 티켓을 확인한다. 이미 있으면 재구매하지 않고 성공으로 복구한다.
  원장 반영이 지연될 수 있으므로 **7초 간격 3회 재조회**(`ledger_has_ticket`) 후에만 '미구매'로 단정한다 —
  1회 조회로 단정하면 결제된 회차를 한 번 더 사게 된다.
- 하드스톱(재시도 안 함)은 **계정·회차 단위** 사유만: 예치금 부족·구매한도·시스템 점검·회차 판매종료.
  구매 전 예치금(1000원)도 선제 확인.
- **번호 단위 안내는 하드스톱보다 우선**한다("판매 마감된 번호입니다"의 '마감'을 회차 마감으로
  오분류하면 정작 필요한 재시도가 죽는다). `_WIN720_RETRY_SPECIFIC`/`_WIN720_RETRY_GENERIC` ↔ `_WIN720_HARD_STOP` 참고.
  범용 부정("구매할 수 없")은 계정 사유의 꼬리일 수 있어, 하드스톱 단어가 없을 때만 번호 사유로 본다.

Java 쪽에서도 화·수·목 11/15시(KST)에 `buyWeekly()`를 재호출한다. 이번 주 미구매 게임만 요청하므로 멱등.

## 테스트
    .venv/bin/pip install pytest
    .venv/bin/python -m pytest test_buy.py -q     # 네트워크·브라우저 불필요

## Java 연동
application.yml:
    lottery.sidecar-command: "python3,/절대경로/sidecar/lottery/buy.py"
크리덴셜은 이 디렉터리 .env 에만 둔다(커밋 금지). Java는 크리덴셜을 다루지 않는다.
