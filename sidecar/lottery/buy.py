#!/usr/bin/env python3
"""동행복권 구매 사이드카. stdout 마지막 줄에 JSON 계약을 출력한다.

계약: {"ok": bool, "balanceAfter": int, "tickets": [...], "errors": [...]}
  tickets[].numbers: 로또 "3,7,12,25,33,41" / 연금 "조:6자리" 예 "3:123456"
로그는 stderr로만 보낸다(stdout은 JSON 전용).
"""
import argparse
import json
import os
import sys

from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))
DH_USER = os.environ.get("DH_USER")
DH_PASSWORD = os.environ.get("DH_PASSWORD")


def log(msg):
    print(msg, file=sys.stderr)


def _lotto_client():
    """dhapi 로그인 클라이언트 반환. dhapi 버전에 맞춰 조정한다."""
    from dhapi.router.dhlottery_client import DhlotteryClient  # dhapi 내부 클라이언트
    client = DhlotteryClient()
    client.login(DH_USER, DH_PASSWORD)
    return client


def get_balance(client):
    """예치금 조회. dhapi의 잔액 조회 API에 맞춰 반환(int 원)."""
    return int(client.get_balance())


def buy_lotto(client, dry_run):
    if dry_run:
        return None
    # 자동 1게임 구매. dhapi buy_lotto645 반환에서 회차·번호를 정규화한다.
    result = client.buy_lotto645(count=1, mode="auto")
    round_no = int(result["round"])
    nums = sorted(int(x) for x in result["numbers"][0])   # 첫 게임 6자리
    return {"gameType": "LOTTO645", "roundNo": round_no,
            "numbers": ",".join(str(n) for n in nums), "amount": 1000}


def buy_win720(dry_run):
    """연금복권720+ 자동 1조 구매(Playwright). 선택자는 라이브에서 codegen으로 확정한다."""
    if dry_run:
        return None
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        # 1) 로그인 → 2) 연금복권720+ 게임 페이지 → 3) 자동선택 → 4) 구매확정
        # 아래 선택자는 `playwright codegen https://dhlottery.co.kr` 로 확정 후 교체한다.
        round_no, jo, digits = _win720_interaction(page)
        browser.close()
    return {"gameType": "WIN720", "roundNo": int(round_no),
            "numbers": f"{jo}:{digits}", "amount": 1000}


def _win720_interaction(page):
    """실제 DOM 조작. techinpark/lottery-bot의 win720 흐름을 참고해 채운다.
    반환: (round_no, jo, digits) — digits는 6자리 문자열."""
    raise NotImplementedError("Playwright 선택자를 라이브 codegen으로 확정 후 구현")


def cmd_balance():
    client = _lotto_client()
    return {"ok": True, "balanceAfter": get_balance(client), "tickets": [], "errors": []}


def cmd_buy(games, dry_run):
    tickets, errors = [], []
    client = _lotto_client()

    if "LOTTO645" in games:
        try:
            t = buy_lotto(client, dry_run)
            if t:
                tickets.append(t)
        except Exception as e:
            errors.append({"gameType": "LOTTO645", "reason": str(e)})

    if "WIN720" in games:
        try:
            t = buy_win720(dry_run)
            if t:
                tickets.append(t)
        except Exception as e:
            errors.append({"gameType": "WIN720", "reason": str(e)})

    balance = get_balance(client)
    return {"ok": len(errors) == 0, "balanceAfter": balance, "tickets": tickets, "errors": errors}


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    buy_p = sub.add_parser("buy")
    buy_p.add_argument("--games", default="LOTTO645,WIN720")
    buy_p.add_argument("--dry-run", action="store_true")
    sub.add_parser("balance")
    args = parser.parse_args()

    if not DH_USER or not DH_PASSWORD:
        print(json.dumps({"ok": False, "balanceAfter": 0, "tickets": [],
                          "errors": [{"gameType": "ALL", "reason": "크리덴셜 미설정(.env)"}]}))
        return

    try:
        if args.cmd == "balance":
            out = cmd_balance()
        else:
            games = [g.strip() for g in args.games.split(",") if g.strip()]
            out = cmd_buy(games, args.dry_run)
    except Exception as e:
        out = {"ok": False, "balanceAfter": 0, "tickets": [],
               "errors": [{"gameType": "ALL", "reason": str(e)}]}

    print(json.dumps(out, ensure_ascii=False))   # stdout 마지막 줄 = JSON


if __name__ == "__main__":
    main()
