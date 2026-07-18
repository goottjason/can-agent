#!/usr/bin/env python3
"""동행복권 구매 사이드카. stdout 마지막 줄에 JSON 계약을 출력한다.

계약: {"ok": bool, "balanceAfter": int, "tickets": [...], "errors": [...]}
  tickets[].numbers: 로또 "3,7,12,25,33,41" / 연금 "조:6자리" 예 "3:123456"
로그는 stderr로만 보낸다(stdout은 JSON 전용).

로또(LOTTO645): dhapi>=4 순수 HTTP(RSA 로그인, OCR 불필요).
  dhapi 4.x는 show_balance/buy_lotto645가 값을 반환하지 않고 endpoint 프린터로
  출력하므로, 값을 캡처하는 _CaptureEndpoint를 주입한다.
연금(WIN720): Playwright — 라이브 codegen 확정 후 구현(Phase C).
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


class _CaptureEndpoint:
    """dhapi LotteryClient가 stdout으로 출력하는 대신 값을 캡처한다.

    dhapi.endpoint.lottery_stdout_printer.LotteryStdoutPrinter 와 동일 인터페이스."""

    def __init__(self):
        self.total = None      # 총예치금
        self.buyable = None    # 구매가능금액(crntEntrsAmt)
        self.slots = None      # buy_lotto645 결과 slot 목록

    def print_result_of_show_balance(self, *, 총예치금, 구매가능금액, 예약구매금액,
                                     출금신청중금액, 구매불가능금액, 최근1달누적구매금액):
        self.total = int(총예치금 or 0)
        self.buyable = int(구매가능금액 or 0)

    def print_result_of_buy_lotto645(self, slots):
        self.slots = slots

    # 미사용 프린터(혹시 호출돼도 안전)
    def print_result_of_assign_virtual_account(self, *a, **k):
        pass

    def print_result_of_show_buy_list(self, *a, **k):
        pass


def _client_and_endpoint():
    """dhapi 4.x LotteryClient 생성(생성자에서 RSA HTTP 자동 로그인) + 캡처 endpoint."""
    from dhapi.domain.user import User
    from dhapi.port.lottery_client import LotteryClient
    ep = _CaptureEndpoint()
    client = LotteryClient(User(DH_USER, DH_PASSWORD), ep)
    return client, ep


def get_balance(client, ep):
    """구매가능금액(원, int). show_balance가 endpoint로 값을 넘긴다."""
    client.show_balance()
    return int(ep.buyable if ep.buyable is not None else 0)


def buy_lotto(client, ep, dry_run):
    if dry_run:
        return None
    from dhapi.domain.lotto645_ticket import Lotto645Ticket
    # 자동 1게임 구매(랜덤). buy_lotto645는 결과를 endpoint.print_result_of_buy_lotto645로 넘긴다.
    tickets = Lotto645Ticket.create_auto_tickets(1)
    client.buy_lotto645(tickets)
    round_no = int(client._get_round())          # dhapi 내부 회차 계산 재사용
    slot = (ep.slots or [{}])[0]
    raw = slot.get("numbers", [])                # 예 ["01","02","04","27","39","44"]
    nums = sorted(int(x) for x in raw)
    return {"gameType": "LOTTO645", "roundNo": round_no,
            "numbers": ",".join(str(n) for n in nums), "amount": 1000}


_WIN720_URL = "https://el.dhlottery.co.kr/game/TotalGame.jsp?LottoId=LP72"


def _session_cookies(client):
    """dhapi LotteryClient의 인증 세션 쿠키를 Playwright 형식으로 변환.
    RSA HTTP 로그인 세션을 재사용하므로 연금 로그인·보안키패드 OCR이 불필요하다."""
    out = []
    for ck in client._session.cookies:
        dom = ck.domain if ck.domain.startswith(".") else "." + ck.domain.lstrip(".")
        out.append({"name": ck.name, "value": ck.value, "domain": dom, "path": ck.path or "/"})
    return out


def buy_win720(client, dry_run):
    """연금복권720+ 자동 1조 1장 구매(Playwright + 세션쿠키 주입, OCR 불필요).

    흐름(라이브 확정, 2026-07-16): 게임 iframe(ifrm_tab) 진입 → 랜덤 단일 조 선택
    → 자동번호(doAuto) → 선택완료(doVerify) → doOrder(확인창 자동수락)
    → doOrderRequest(실구매). '모든 조'는 5장(5000원)이 되므로 단일 조로 1장만 산다."""
    if dry_run:
        return None
    import random
    from playwright.sync_api import sync_playwright

    jo = str(random.randint(1, 5))
    cookies = _session_cookies(client)
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1100, "height": 900})
        ctx.add_cookies(cookies)
        page = ctx.new_page()
        page.on("dialog", lambda d: d.accept())   # doOrder 확인창 자동수락
        try:
            round_no, jo_num, digits = _win720_interaction(page, jo)
        finally:
            browser.close()
    return {"gameType": "WIN720", "roundNo": int(round_no),
            "numbers": f"{jo_num}:{digits}", "amount": 1000}


def _win720_interaction(page, jo):
    """연금 게임 iframe DOM 조작. 반환: (round_no, jo, digits)."""
    page.goto(_WIN720_URL, timeout=25000, wait_until="networkidle")
    page.wait_for_timeout(2500)
    fr = page.frame(name="ifrm_tab")
    if fr is None:
        raise RuntimeError("연금 게임 프레임(ifrm_tab)을 찾지 못함")
    # 인트로 팝업 닫기(있으면)
    for el in fr.query_selector_all("a.lotto720_popup_bottom_btn_close"):
        try:
            if el.is_visible():
                el.click(); page.wait_for_timeout(300)
        except Exception:
            pass
    fr.click(f"span.lotto720_box.jogroup.num{jo}"); page.wait_for_timeout(400)   # 단일 조
    fr.click("a.lotto720_btn_auto_number"); page.wait_for_timeout(700)           # 자동 6자리
    fr.click("a.lotto720_btn_confirm_number"); page.wait_for_timeout(900)        # 선택 완료(doVerify)
    buyno = fr.evaluate("()=>{var e=document.querySelector('#frm input[name=BUY_NO]'); return e?e.value:'';}")
    rnd = fr.evaluate("()=>{var e=document.getElementById('DROUND')||document.getElementById('ROUND'); return e?e.value:'';}")
    if not buyno or not rnd:
        raise RuntimeError(f"연금 번호/회차 미확정 (BUY_NO={buyno!r}, ROUND={rnd!r})")
    # 클릭은 확인창과 엉키므로 JS 함수 직접 호출
    fr.evaluate("()=>doOrder()"); page.wait_for_timeout(1500)                    # 확인팝업(확인창 자동수락)
    fr.evaluate("()=>doOrderRequest()"); page.wait_for_timeout(6000)            # 실구매
    body = fr.inner_text("body")
    if "구매가 완료" not in body and "구매완료" not in body:
        raise RuntimeError("연금 구매 완료 미확인: " + " ".join(body.split())[:200])
    return int(rnd), buyno[0], buyno[1:]   # BUY_NO 예 "4481478" → (324, "4", "481478")


_LEDGER_URL = "https://www.dhlottery.co.kr/mypage/mylotteryledger"
_GAME_MAP = {"로또6/45": "LOTTO645", "연금복권720+": "WIN720", "연금복권720": "WIN720"}
_STATUS_MAP = {"당첨": "WIN", "낙첨": "LOSE", "추첨중": "PENDING", "미추첨": "PENDING"}


def _parse_ledger_row(text):
    """MY 당첨내역 행 텍스트 → {gameType, roundNo, status, winAmount, drawDate}."""
    import re
    t = " ".join(text.split())
    gk = next((k for k in _GAME_MAP if k in t), None)
    if gk is None:
        return None
    m_round = re.search(re.escape(gk) + r"\s+(\d+)", t)   # 게임명 뒤 첫 정수 = 회차
    if not m_round:
        return None
    status = next((v for k, v in _STATUS_MAP.items() if k in t), None)
    m_amt = re.search(r"([\d,]+)\s*원", t)
    m_draw = re.search(r"추첨일자\s*(\d{4}-\d{2}-\d{2})", t)
    return {"gameType": _GAME_MAP[gk], "roundNo": int(m_round.group(1)),
            "status": status,
            "winAmount": int(m_amt.group(1).replace(",", "")) if m_amt else 0,
            "drawDate": m_draw.group(1) if m_draw else None}


def check_results(client):
    """인증 브라우저로 MY 구매/당첨 내역을 스크래핑해 게임·회차별 당첨/낙첨 반환.

    공개 당첨번호 API가 서버(데이터센터) IP에서 차단되므로(익명 조회 302→홈),
    구매와 동일한 인증 세션 쿠키를 브라우저에 주입해 MY 당첨내역을 읽는다."""
    from playwright.sync_api import sync_playwright
    cookies = _session_cookies(client)
    rows = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1280, "height": 1400})
        ctx.add_cookies(cookies)
        page = ctx.new_page()
        try:
            page.goto(_LEDGER_URL, timeout=25000, wait_until="networkidle")
            page.wait_for_timeout(1500)
            try:   # 조회기간 1개월 + 검색(기본 '당일'이면 최근 티켓 누락 방지)
                page.get_by_text("1개월", exact=True).first.click(); page.wait_for_timeout(300)
                page.get_by_role("button", name="검색").first.click(); page.wait_for_timeout(1800)
            except Exception:
                pass
            rows = page.evaluate("""() => Array.from(document.querySelectorAll('table tbody tr, ul li'))
                .map(e=>e.innerText.replace(/\\s+/g,' ').trim())
                .filter(t=>/로또6\\/45|연금복권720/.test(t) && /구입일자/.test(t) && t.length<200)""")
        finally:
            browser.close()
    seen, out = set(), []
    for text in rows:
        r = _parse_ledger_row(text)
        if r and (r["gameType"], r["roundNo"]) not in seen:
            seen.add((r["gameType"], r["roundNo"]))
            out.append(r)
    return out


def cmd_result():
    client, _ = _client_and_endpoint()
    return {"ok": True, "results": check_results(client), "errors": []}


def cmd_balance():
    client, ep = _client_and_endpoint()
    return {"ok": True, "balanceAfter": get_balance(client, ep), "tickets": [], "errors": []}


def cmd_buy(games, dry_run):
    tickets, errors = [], []
    client, ep = _client_and_endpoint()

    if "LOTTO645" in games:
        try:
            t = buy_lotto(client, ep, dry_run)
            if t:
                tickets.append(t)
        except Exception as e:
            errors.append({"gameType": "LOTTO645", "reason": str(e)})

    if "WIN720" in games:
        try:
            t = buy_win720(client, dry_run)
            if t:
                tickets.append(t)
        except Exception as e:
            errors.append({"gameType": "WIN720", "reason": str(e)})

    balance = get_balance(client, ep)
    return {"ok": len(errors) == 0, "balanceAfter": balance, "tickets": tickets, "errors": errors}


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    buy_p = sub.add_parser("buy")
    buy_p.add_argument("--games", default="LOTTO645,WIN720")
    buy_p.add_argument("--dry-run", action="store_true")
    sub.add_parser("balance")
    sub.add_parser("result")
    args = parser.parse_args()

    if not DH_USER or not DH_PASSWORD:
        print(json.dumps({"ok": False, "balanceAfter": 0, "tickets": [], "results": [],
                          "errors": [{"gameType": "ALL", "reason": "크리덴셜 미설정(.env)"}]}))
        return

    try:
        if args.cmd == "balance":
            out = cmd_balance()
        elif args.cmd == "result":
            out = cmd_result()
        else:
            games = [g.strip() for g in args.games.split(",") if g.strip()]
            out = cmd_buy(games, args.dry_run)
    except Exception as e:
        out = {"ok": False, "balanceAfter": 0, "tickets": [], "results": [],
               "errors": [{"gameType": "ALL", "reason": str(e)}]}

    print(json.dumps(out, ensure_ascii=False))   # stdout 마지막 줄 = JSON


if __name__ == "__main__":
    main()
