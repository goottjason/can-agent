"""사이드카 순수 로직 테스트(네트워크·브라우저 없음).

실행: cd sidecar/lottery && .venv/bin/python -m pytest test_buy.py -q
"""
import pytest

# playwright/dhapi는 함수 안에서만 import하므로 모듈 로드에는 python-dotenv만 필요하다.
import buy


class TestHardStopReason:
    def test_none_when_no_keyword(self):
        assert buy._hard_stop_reason(["구매가 완료되었습니다"]) is None

    def test_none_for_empty(self):
        assert buy._hard_stop_reason([]) is None
        assert buy._hard_stop_reason([None, ""]) is None

    @pytest.mark.parametrize("msg", [
        "예치금이 부족합니다.",
        "1인당 구매한도를 초과했습니다.",
        "판매가 마감되었습니다.",
        "시스템 점검중입니다.",
    ])
    def test_detects_non_retryable(self, msg):
        assert buy._hard_stop_reason([msg]) == msg

    def test_returns_first_match(self):
        assert buy._hard_stop_reason(["정상 안내", "예치금 부족"]) == "예치금 부족"


class TestNumberLevelIsRetryable:
    """번호 단위 사유는 '다른 번호로 다시'가 정답 — 하드스톱으로 오분류되면 재시도가 죽는다."""

    @pytest.mark.parametrize("msg", [
        "이미 판매된 번호입니다.",
        "판매 마감된 번호입니다. 다른 번호를 선택해 주세요.",
        "선택하신 번호는 구매할 수 없습니다.",
        "해당 번호는 잔여 수량이 부족합니다.",
        "매진된 번호입니다.",
    ])
    def test_number_level_never_hard_stops(self, msg):
        assert buy._retry_hint([msg]) == msg
        assert buy._hard_stop_reason([msg]) is None     # '마감'·'부족'이 들어 있어도 재시도한다

    @pytest.mark.parametrize("msg", ["구매 제한 안내", "중복 선택되었습니다", "판매 마감까지 남은시간"])
    def test_bare_keywords_no_longer_hard_stop(self, msg):
        """단어 하나('마감'·'제한'·'중복')로 재시도를 끊지 않는다 — 정상/번호 안내와 겹친다."""
        assert buy._hard_stop_reason([msg]) is None

    def test_retry_hint_wins_over_hard_stop_in_same_batch(self):
        msgs = ["예치금이 부족합니다.", "이미 판매된 번호입니다."]
        assert buy._hard_stop_reason(msgs) is None

    @pytest.mark.parametrize("msg", [
        "구매한도 초과로 구매하실 수 없습니다.",
        "예치금이 부족하여 구매할 수 없습니다.",
    ])
    def test_generic_negation_does_not_mask_account_reason(self, msg):
        """범용 부정("구매할 수 없")은 계정 사유의 꼬리일 수 있다 — 헛된 8분 재시도를 막는다."""
        assert buy._retry_hint([msg]) is None
        assert buy._hard_stop_reason([msg]) == msg

    def test_generic_negation_alone_is_retryable(self):
        msg = "선택하신 번호는 구매할 수 없습니다."
        assert buy._retry_hint([msg]) == msg
        assert buy._hard_stop_reason([msg]) is None


class TestWin720Ticket:
    def test_splits_buy_no_into_jo_and_digits(self):
        assert buy._win720_ticket("330", "4481478") == {
            "gameType": "WIN720", "roundNo": 330, "numbers": "4:481478", "amount": 1000}


class FakePage:
    """Playwright Page 스텁 — 재조회 대기만 흉내낸다."""

    def __init__(self):
        self.waits = []

    def wait_for_timeout(self, ms):
        self.waits.append(ms)


class TestLedgerGuard:
    """중복구매 차단의 핵심 — 원장에 해당 회차가 있으면 재구매하지 않는다."""

    @pytest.fixture
    def page(self, monkeypatch):
        pg = FakePage()
        monkeypatch.setattr(buy, "_with_ledger_page", lambda client, fn: fn(pg))
        return pg

    def _scrapes(self, monkeypatch, *batches):
        """호출 순서대로 원장 행을 돌려주는 스텁(마지막 배치는 이후에도 반복)."""
        seq = list(batches)
        calls = {"n": 0}

        def scrape(page):
            i = min(calls["n"], len(seq) - 1)
            calls["n"] += 1
            return seq[i]

        monkeypatch.setattr(buy, "_scrape_ledger", scrape)
        return calls

    def test_true_when_round_present(self, monkeypatch, page):
        calls = self._scrapes(monkeypatch, [{"gameType": "WIN720", "roundNo": 330}])
        assert buy._win720_purchased_in_ledger(None, "330") is True
        assert calls["n"] == 1          # 첫 조회에서 확인되면 더 안 본다
        assert page.waits == []

    def test_false_when_other_round(self, monkeypatch, page):
        self._scrapes(monkeypatch, [{"gameType": "WIN720", "roundNo": 329}])
        assert buy._win720_purchased_in_ledger(None, 330) is False

    def test_false_when_other_game_same_round(self, monkeypatch, page):
        self._scrapes(monkeypatch, [{"gameType": "LOTTO645", "roundNo": 330}])
        assert buy._win720_purchased_in_ledger(None, 330) is False

    def test_rechecks_when_ledger_lags(self, monkeypatch, page):
        """구매 직후 원장 반영이 늦으면 1회 조회로 '미구매' 단정 금지 — 재조회로 잡아낸다."""
        calls = self._scrapes(monkeypatch, [], [], [{"gameType": "WIN720", "roundNo": 330}])

        assert buy._win720_purchased_in_ledger(None, 330) is True
        assert calls["n"] == 3
        assert page.waits == [7000, 7000]

    def test_gives_up_after_all_rechecks(self, monkeypatch, page):
        calls = self._scrapes(monkeypatch, [])
        assert buy._win720_purchased_in_ledger(None, 330) is False
        assert calls["n"] == 3          # 기본 3회 재조회를 모두 소진한 뒤에만 미구매 판정

    def test_ledger_failure_is_hard_stop(self, monkeypatch):
        def boom(client, fn):
            raise RuntimeError("원장 접속 실패")
        monkeypatch.setattr(buy, "_with_ledger_page", boom)
        with pytest.raises(buy.Win720Error) as ei:
            buy._win720_purchased_in_ledger(None, 330)
        assert ei.value.hard_stop is True   # 확인 불가 상태에서 재구매 금지


class TestBuyWin720Retry:
    """buy_win720의 재시도·복구 규약. _win720_attempt를 가짜로 대체해 검증한다."""

    @pytest.fixture(autouse=True)
    def no_sleep(self, monkeypatch):
        monkeypatch.setattr("time.sleep", lambda s: None)   # 백오프 대기 제거

    def test_dry_run_returns_none(self):
        assert buy.buy_win720(None, dry_run=True) is None

    def test_succeeds_on_second_attempt(self, monkeypatch):
        calls = {"n": 0}

        def attempt(client):
            calls["n"] += 1
            if calls["n"] == 1:
                raise buy.Win720Error("프레임 없음")       # round_no 없음 = 주문 미발생
            return {"gameType": "WIN720", "roundNo": 330, "numbers": "1:234567", "amount": 1000}

        monkeypatch.setattr(buy, "_win720_attempt", attempt)
        assert buy.buy_win720(object(), dry_run=False, attempts=3)["roundNo"] == 330
        assert calls["n"] == 2

    def test_recovers_from_ledger_without_rebuying(self, monkeypatch):
        calls = {"n": 0}

        def attempt(client):
            calls["n"] += 1
            raise buy.Win720Error("연금 구매 완료 미확인: ...", round_no="330", buy_no="4481478")

        monkeypatch.setattr(buy, "_win720_attempt", attempt)
        monkeypatch.setattr(buy, "ledger_has_ticket",
                            lambda client, game, rnd, **kw: True)

        t = buy.buy_win720(object(), dry_run=False, attempts=3)

        assert t == {"gameType": "WIN720", "roundNo": 330, "numbers": "4:481478", "amount": 1000}
        assert calls["n"] == 1          # 재구매 시도 없음

    def test_retries_when_ledger_has_no_ticket(self, monkeypatch):
        calls = {"n": 0}

        def attempt(client):
            calls["n"] += 1
            raise buy.Win720Error("완료 미확인", round_no="330", buy_no="4481478")

        monkeypatch.setattr(buy, "_win720_attempt", attempt)
        monkeypatch.setattr(buy, "ledger_has_ticket",
                            lambda client, game, rnd, **kw: False)

        with pytest.raises(RuntimeError, match="3회 시도 모두 실패"):
            buy.buy_win720(object(), dry_run=False, attempts=3)
        assert calls["n"] == 3

    def test_hard_stop_does_not_retry(self, monkeypatch):
        calls = {"n": 0}

        def attempt(client):
            calls["n"] += 1
            raise buy.Win720Error("예치금이 부족합니다", hard_stop=True)

        monkeypatch.setattr(buy, "_win720_attempt", attempt)
        with pytest.raises(RuntimeError, match="재시도 무의미"):
            buy.buy_win720(object(), dry_run=False, attempts=3)
        assert calls["n"] == 1

    def test_unexpected_exception_is_retried(self, monkeypatch):
        calls = {"n": 0}

        def attempt(client):
            calls["n"] += 1
            if calls["n"] < 3:
                raise TimeoutError("Timeout 25000ms exceeded")
            return {"gameType": "WIN720", "roundNo": 331, "numbers": "2:111111", "amount": 1000}

        monkeypatch.setattr(buy, "_win720_attempt", attempt)
        assert buy.buy_win720(object(), dry_run=False, attempts=3)["roundNo"] == 331
        assert calls["n"] == 3


class TestHardStopScope:
    """정상 확인창(doOrder "구매하시겠습니까")이 하드스톱으로 오분류되면 재시도가 조용히 죽는다."""

    def test_ignores_dialogs_before_order_mark(self):
        dialogs = ["구매 제한 안내 팝업"]          # 주문 이전(인트로·확인창)
        page = FakePage()
        fr = _FakeFrame("아직 처리중")

        # mark=1 → 주문 이후 새 확인창이 없으므로 하드스톱 조기중단 없이 폴링만 하다 실패
        assert buy._wait_purchase_done(page, fr, dialogs, mark=1, timeout_ms=2000) is False
        assert page.waits == [1000, 1000]        # 조기 중단이 아니라 끝까지 폴링했다

    def test_stops_early_on_dialog_after_order(self):
        dialogs = ["구매하시겠습니까?", "예치금이 부족합니다."]
        page = FakePage()
        fr = _FakeFrame("아직 처리중")

        assert buy._wait_purchase_done(page, fr, dialogs, mark=1, timeout_ms=20000) is False
        assert page.waits == []                  # 즉시 중단

    def test_detects_completion_text(self):
        page = FakePage()
        fr = _FakeFrame("구매가 완료되었습니다")

        assert buy._wait_purchase_done(page, fr, [], mark=0, timeout_ms=20000) is True


class _FakeFrame:
    def __init__(self, body):
        self._body = body

    def inner_text(self, sel):
        return self._body


class _Win720Page:
    """연금 게임 페이지 스텁 — 조 선택 → 자동번호 → 선택완료 흐름만 흉내낸다."""

    def __init__(self, frame):
        self._frame = frame
        self.waits = []

    def goto(self, url, **kw):
        pass

    def wait_for_timeout(self, ms):
        self.waits.append(ms)

    def frame(self, name=None):
        return self._frame


class _Win720Frame:
    def __init__(self, ok_jo, dialogs, reject_msg=None, body="구매가 완료되었습니다"):
        self.ok_jo = ok_jo          # 이 조에서만 번호가 확정된다(나머지는 소진)
        self.dialogs = dialogs
        self.reject_msg = reject_msg
        self.body = body
        self.tried = []
        self._cur = None
        self._buyno = self._rnd = ""

    def query_selector_all(self, sel):
        return []

    def click(self, sel):
        if "jogroup.num" in sel:
            self._cur = sel[-1]
            self.tried.append(self._cur)
        elif "confirm_number" in sel:
            if self._cur == self.ok_jo:
                self._buyno, self._rnd = self._cur + "123456", "331"
            else:
                self._buyno = self._rnd = ""
                if self.reject_msg:
                    self.dialogs.append(self.reject_msg)

    def evaluate(self, js):
        if "BUY_NO" in js:
            return self._buyno
        if "DROUND" in js:
            return self._rnd
        return ""

    def inner_text(self, sel):
        return self.body


class TestNumberRepick:
    """소진된 번호를 만나면 브라우저를 다시 띄우지 않고 같은 페이지에서 다른 조로 다시 고른다."""

    def test_repicks_until_a_jo_confirms(self):
        dialogs = []
        fr = _Win720Frame(ok_jo="3", dialogs=dialogs, reject_msg="이미 판매된 번호입니다.")
        page = _Win720Page(fr)

        rnd, buyno = buy._win720_interaction(page, ["1", "2", "3"], dialogs)

        assert (rnd, buyno) == (331, "3123456")
        assert fr.tried == ["1", "2", "3"]          # 조를 바꿔가며 재선택했다

    def test_stops_repicking_on_account_level_reason(self):
        dialogs = []
        fr = _Win720Frame(ok_jo="9", dialogs=dialogs, reject_msg="예치금이 부족합니다.")
        page = _Win720Page(fr)

        with pytest.raises(buy.Win720Error) as ei:
            buy._win720_interaction(page, ["1", "2", "3"], dialogs)

        assert ei.value.hard_stop is True
        assert fr.tried == ["1"]                    # 조를 바꿔도 소용없으므로 즉시 중단

    def test_reports_tried_jo_when_all_exhausted(self):
        dialogs = []
        fr = _Win720Frame(ok_jo="9", dialogs=dialogs, reject_msg="이미 판매된 번호입니다.")
        page = _Win720Page(fr)

        with pytest.raises(buy.Win720Error) as ei:
            buy._win720_interaction(page, ["1", "2", "3"], dialogs)

        assert ei.value.hard_stop is False           # 다음 시도에서 다시 해볼 가치가 있다
        assert "시도한 조" in str(ei.value)
        assert fr.tried == ["1", "2", "3"]
