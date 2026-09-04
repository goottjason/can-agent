#!/usr/bin/env python3
"""동행복권 구매 사이드카. stdout 마지막 줄에 JSON 계약을 출력한다.

계약: {"ok": bool, "balanceAfter": int, "tickets": [...], "errors": [...]}
  tickets[].numbers: 로또 "3,7,12,25,33,41" / 연금 "조:6자리" 예 "3:123456"
로그는 stderr로만 보낸다(stdout은 JSON 전용).

로또(LOTTO645): dhapi>=4 순수 HTTP(RSA 로그인, OCR 불필요).
  dhapi 4.x는 show_balance/buy_lotto645가 값을 반환하지 않고 endpoint 프린터로
  출력하므로, 값을 캡처하는 _CaptureEndpoint를 주입한다.
연금(WIN720): Playwright(세션쿠키 주입). 실패 시 재시도하되, 이미 결제된 회차는
  MY 당첨내역 원장으로 확인해 중복구매를 막는다.
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


# 재시도해도 결과가 같고 중복구매만 유발하는 사유(하드스톱). 감지 시 즉시 중단한다.
_WIN720_HARD_STOP = ("부족", "한도", "마감", "종료", "점검", "제한", "중복", "본인확인")
_WIN720_DEFAULT_ATTEMPTS = 3
_WIN720_BACKOFF_SEC = (5, 15, 30)      # attempt 1,2,3 실패 후 대기


def _hard_stop_reason(texts):
    """재시도 무의미 사유가 담긴 첫 텍스트를 반환(없으면 None)."""
    for t in texts:
        if t and any(k in t for k in _WIN720_HARD_STOP):
            return t
    return None


class Win720Error(Exception):
    """연금 구매 실패. round_no가 있으면 주문 요청이 나갔을 수 있어 원장 확인이 필요하다."""

    def __init__(self, message, round_no=None, buy_no=None, hard_stop=False):
        super().__init__(message)
        self.round_no = round_no
        self.buy_no = buy_no
        self.hard_stop = hard_stop


def _win720_ticket(round_no, buy_no):
    """BUY_NO 예 "4481478" → 조 "4" + 6자리 "481478"."""
    return {"gameType": "WIN720", "roundNo": int(round_no),
            "numbers": f"{buy_no[0]}:{buy_no[1:]}", "amount": 1000}


def _win720_purchased_in_ledger(client, round_no):
    """MY 당첨내역에 해당 회차 연금 티켓이 이미 있으면 True.

    '완료 미확인'으로 실패했지만 실제로는 결제가 끝난 경우를 잡아내 중복구매를 막는다.
    연금은 주 1회차이므로 (WIN720, 회차)가 곧 이번 주 구매 여부다.
    원장 반영 지연을 감안해 여러 번 재조회한다(1회 조회로 '미구매' 단정 금지 — 중복결제 방지)."""
    try:
        return ledger_has_ticket(client, "WIN720", round_no)
    except Exception as e:                       # 원장 확인 실패 시 재시도 금지(안전측)
        raise Win720Error(f"연금 구매 확인 실패(원장 조회 불가): {e}", hard_stop=True) from e


def buy_win720(client, dry_run, attempts=None):
    """연금복권720+ 자동 1조 1장 구매. 실패 시 재시도하되 중복구매는 원장으로 차단한다.

    흐름(라이브 확정, 2026-07-16): 게임 iframe(ifrm_tab) 진입 → 랜덤 단일 조 선택
    → 자동번호(doAuto) → 선택완료(doVerify) → doOrder(확인창 자동수락)
    → doOrderRequest(실구매). '모든 조'는 5장(5000원)이 되므로 단일 조로 1장만 산다.

    재시도 규약:
      - BUY_NO/회차 확보 후 실패 = 주문이 나갔을 수 있음 → 원장에서 해당 회차 확인.
        이미 있으면 '복구 성공'으로 티켓을 반환하고 재구매하지 않는다.
      - 예치금 부족·판매 마감 등 하드스톱 사유는 재시도하지 않는다."""
    if dry_run:
        return None
    import time
    attempts = attempts or int(os.environ.get("WIN720_ATTEMPTS", _WIN720_DEFAULT_ATTEMPTS))
    last = None
    for i in range(1, attempts + 1):
        try:
            return _win720_attempt(client)
        except Exception as raw:
            # Playwright 타임아웃 등 예상 밖 예외도 재시도 대상으로 정규화한다.
            e = raw if isinstance(raw, Win720Error) else Win720Error(str(raw) or type(raw).__name__)
            last = e
            log(f"[WIN720] 시도 {i}/{attempts} 실패: {e}")
            if e.round_no:                       # 주문이 나갔을 수 있다 → 실제 구매 여부 확인
                if _win720_purchased_in_ledger(client, e.round_no):
                    log(f"[WIN720] 원장에서 {e.round_no}회 구매 확인 — 완료 판정만 실패했음(재구매 안 함)")
                    return _win720_ticket(e.round_no, e.buy_no) if e.buy_no else \
                        {"gameType": "WIN720", "roundNo": int(e.round_no), "numbers": "", "amount": 1000}
            if e.hard_stop:
                raise RuntimeError(f"연금 구매 중단(재시도 무의미): {e}") from e
            if i < attempts:
                time.sleep(_WIN720_BACKOFF_SEC[min(i - 1, len(_WIN720_BACKOFF_SEC) - 1)])
    raise RuntimeError(f"연금 구매 {attempts}회 시도 모두 실패: {last}")


def _win720_attempt(client):
    """단일 시도: 새 브라우저 컨텍스트로 구매를 끝까지 수행한다."""
    import random
    from playwright.sync_api import sync_playwright

    jo = str(random.randint(1, 5))
    cookies = _session_cookies(client)
    dialogs = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1100, "height": 900})
        ctx.add_cookies(cookies)
        page = ctx.new_page()
        # 확인창은 수락하되 문구를 남긴다 — 조용한 실패(사유 유실)를 막는 핵심.
        page.on("dialog", lambda d: (dialogs.append(d.message), d.accept()))
        try:
            round_no, buy_no = _win720_interaction(page, jo, dialogs)
        finally:
            browser.close()
    return _win720_ticket(round_no, buy_no)


def _win720_interaction(page, jo, dialogs):
    """연금 게임 iframe DOM 조작. 반환: (round_no, buy_no)."""
    page.goto(_WIN720_URL, timeout=25000, wait_until="networkidle")
    page.wait_for_timeout(2500)
    fr = page.frame(name="ifrm_tab")
    if fr is None:
        raise Win720Error("연금 게임 프레임(ifrm_tab)을 찾지 못함")
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
        stop = _hard_stop_reason(dialogs)
        raise Win720Error(f"연금 번호/회차 미확정 (BUY_NO={buyno!r}, ROUND={rnd!r})"
                          + (f" / 안내: {stop}" if stop else ""), hard_stop=bool(stop))
    # 여기부터 주문이 나갈 수 있다 — 이후 실패는 반드시 round_no를 달고 던진다(원장 확인용).
    # 클릭은 확인창과 엉키므로 JS 함수 직접 호출
    try:
        fr.evaluate("()=>doOrder()"); page.wait_for_timeout(1500)                # 확인팝업(확인창 자동수락)
        # 여기까지의 확인창(인트로 팝업·"구매하시겠습니까")은 정상 흐름이므로 하드스톱 판정에서 제외한다.
        # 이후 뜨는 안내창만 실패 사유 후보 — 정상 문구가 키워드와 겹쳐 재시도가 무력화되는 것을 막는다.
        mark = len(dialogs)
        fr.evaluate("()=>doOrderRequest()")                                      # 실구매
    except Exception as e:
        raise Win720Error(f"연금 주문 호출 실패: {e}", round_no=rnd, buy_no=buyno) from e
    if _wait_purchase_done(page, fr, dialogs, mark):
        return int(rnd), buyno
    stop = _hard_stop_reason(dialogs[mark:])
    detail = " / ".join(dialogs)[:200] if dialogs else _page_snippet(fr)
    raise Win720Error("연금 구매 완료 미확인: " + detail,
                      round_no=rnd, buy_no=buyno, hard_stop=bool(stop))


def _page_snippet(fr):
    try:
        return " ".join(fr.inner_text("body").split())[:200]
    except Exception:
        return "(본문 읽기 실패)"


def _wait_purchase_done(page, fr, dialogs, mark=0, timeout_ms=20000):
    """구매 완료 문구를 최대 timeout_ms 폴링. 주문 이후 뜬 하드스톱 안내창이면 조기 중단.

    고정 대기(6초)는 사이트가 느린 날 구매 성공을 실패로 오판했다 → 폴링으로 교체."""
    waited = 0
    while waited < timeout_ms:
        if _hard_stop_reason(dialogs[mark:]):
            return False
        try:
            body = " ".join(fr.inner_text("body").split())
        except Exception:
            body = ""
        if "구매가 완료" in body or "구매완료" in body:
            return True
        page.wait_for_timeout(1000)
        waited += 1000
    return False


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


def _scrape_ledger(page):
    """열린 페이지에서 MY 당첨내역을 1회 조회해 파싱된 행 목록을 반환."""
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
    seen, out = set(), []
    for text in rows:
        r = _parse_ledger_row(text)
        if r and (r["gameType"], r["roundNo"]) not in seen:
            seen.add((r["gameType"], r["roundNo"]))
            out.append(r)
    return out


def _with_ledger_page(client, fn):
    """인증 쿠키를 주입한 브라우저 페이지를 열어 fn(page)을 수행한다."""
    from playwright.sync_api import sync_playwright
    cookies = _session_cookies(client)
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1280, "height": 1400})
        ctx.add_cookies(cookies)
        page = ctx.new_page()
        try:
            return fn(page)
        finally:
            browser.close()


def check_results(client):
    """인증 브라우저로 MY 구매/당첨 내역을 스크래핑해 게임·회차별 당첨/낙첨 반환.

    공개 당첨번호 API가 서버(데이터센터) IP에서 차단되므로(익명 조회 302→홈),
    구매와 동일한 인증 세션 쿠키를 브라우저에 주입해 MY 당첨내역을 읽는다."""
    return _with_ledger_page(client, _scrape_ledger)


def ledger_has_ticket(client, game_type, round_no, tries=3, gap_ms=7000):
    """원장에 (게임, 회차) 티켓이 보일 때까지 tries회 재조회. 한 번이라도 보이면 True.

    구매 직후 원장 반영이 지연될 수 있어 1회 조회로 '미구매'를 단정하면 중복결제가 난다.
    브라우저는 한 번만 띄우고 재조회만 반복해 비용을 낮춘다."""
    def probe(page):
        for i in range(tries):
            if any(r["gameType"] == game_type and r["roundNo"] == int(round_no)
                   for r in _scrape_ledger(page)):
                return True
            if i < tries - 1:
                log(f"[LEDGER] {game_type} {round_no}회 미반영 — {gap_ms}ms 후 재조회 ({i + 1}/{tries})")
                page.wait_for_timeout(gap_ms)
        return False
    return _with_ledger_page(client, probe)


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
            # 예치금이 모자라면 브라우저를 띄워봐야 확인창만 뜬다 → 선제 차단(재시도 낭비 방지).
            if not dry_run:
                buyable = get_balance(client, ep)
                if buyable < 1000:
                    raise RuntimeError(f"예치금 부족(구매가능 {buyable}원, 필요 1000원)")
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
