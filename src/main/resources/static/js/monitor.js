// 실시간 모니터링 대시보드 폴링 (R1 하트비트 + R2 리더보드 + R3 퍼널/부족요소 + R4 실행 차단 사유).
// 모든 데이터를 단일 엔드포인트 GET /api/monitor/status에서 받아 렌더한다(backend 확정 shape).
// 3단계 상태 판정은 backend가 내려주는 status 값을 그대로 신뢰한다 — 프론트 자체 시계로 이중 판정하지 않는다.
// 폴링 실패 시 조용히 이전 값을 유지하지 않고 화면 상단에 오류 상태를 명시한다(이 프로젝트의 고질병 방지).
(function () {
    'use strict';

    var STATUS_URL = document.body.getAttribute('data-monitor-status-url');
    var POLL_INTERVAL_MS = 20000; // 15~30초 권장 범위
    var COUNTDOWN_TICK_MS = 1000;

    // status(3단계) → pill 클래스·라벨. backend 확정 enum: RUNNING | STALE | CLOSED.
    var STATUS_MAP = {
        RUNNING: { cls: 'running', label: '가동 중' },
        STALE: { cls: 'stale', label: '지연' },
        CLOSED: { cls: 'off-hours', label: '장외 대기' }
    };

    // 퍼널 단계 정의(backend funnel 키 ↔ 라벨 ↔ 카드 스타일).
    var FUNNEL_STAGES = [
        { key: 'scanned', label: '스캔 대상', cls: '' },
        { key: 'priceFail', label: '현재가 실패', cls: 'drop' },
        { key: 'heldSkip', label: '보유 제외', cls: 'drop' },
        { key: 'signalMiss', label: '신호 미달', cls: 'drop' },
        { key: 'scoreMiss', label: '총점 미달', cls: 'drop' },
        { key: 'signals', label: '신호', cls: 'signal' },
        { key: 'orderSuccess', label: '주문 성공', cls: 'signal' },
        { key: 'orderBlocked', label: '주문 차단', cls: 'drop' }
    ];

    // 신호 실행 상태(R4) → 표시 라벨·스타일. backend 확정 enum.
    var EXEC_STATUS_MAP = {
        ORDER_SUCCESS: { cls: 'ordered', label: '주문 완료' },
        INSUFFICIENT_CASH: { cls: 'blocked', label: '예수금 부족' },
        LIMIT_REACHED: { cls: 'blocked', label: '한도 도달' },
        MIN_AMOUNT: { cls: 'blocked', label: '최소주문 미달' },
        UNAFFORDABLE: { cls: 'blocked', label: '예산 초과' },
        MARKET_CLOSED: { cls: 'blocked', label: '장 마감' },
        BALANCE_FAIL: { cls: 'failed', label: '잔고 조회 실패' },
        ORDER_FAILED: { cls: 'failed', label: '주문 실패' }
    };

    // 요소별 점수 컬럼(C·A·S·L·I·M). nearMiss/leaderboard 공통 키 매핑.
    var ELEMENTS = [
        { key: 'quarterly', label: 'C' },
        { key: 'annual', label: 'A' },
        { key: 'supplyDemand', label: 'S' },
        { key: 'industryLeader', label: 'L' },
        { key: 'institutional', label: 'I' },
        { key: 'marketDirection', label: 'M' }
    ];

    var el = {
        pill: document.getElementById('mon-status-pill'),
        pollError: document.getElementById('mon-poll-error'),
        lastCheck: document.getElementById('mon-last-check'),
        countdown: document.getElementById('mon-countdown'),
        todayCount: document.getElementById('mon-today-count'),
        scanCount: document.getElementById('mon-scan-count'),
        scanMetric: document.getElementById('mon-scan-metric'),
        minScore: document.getElementById('mon-min-score'),
        funnel: document.getElementById('mon-funnel'),
        nearMiss: document.getElementById('mon-near-miss'),
        leaderboardBody: document.getElementById('mon-leaderboard-body'),
        signalsBody: document.getElementById('mon-signals-body'),
        signalsWrap: document.getElementById('mon-signals-wrap'),
        signalsEmpty: document.getElementById('mon-signals-empty')
    };

    var nextCheckAtMs = null; // 카운트다운 목표 시각(epoch ms)

    function esc(v) {
        return String(v == null ? '' : v)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function fmtInt(n) {
        return (n == null) ? '-' : Number(n).toLocaleString('en-US');
    }

    function fmtMoney(n) {
        return (n == null) ? '-' : '$' + Number(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function fmtTime(isoString) {
        if (!isoString) return '-';
        var d = new Date(isoString);
        if (isNaN(d.getTime())) return isoString;
        return d.toLocaleTimeString('ko-KR', { hour12: false });
    }

    function setPill(cls, label) {
        if (!el.pill) return;
        el.pill.className = 'status-pill ' + cls;
        el.pill.textContent = label;
    }

    function showPollError(message) {
        setPill('error', '상태 확인 실패');
        if (el.pollError) {
            el.pollError.textContent = message;
            el.pollError.style.display = 'block';
        }
    }

    function clearPollError() {
        if (el.pollError) el.pollError.style.display = 'none';
    }

    function tickCountdown() {
        if (!el.countdown) return;
        if (nextCheckAtMs === null) {
            el.countdown.textContent = '-';
            return;
        }
        var remainSec = Math.round((nextCheckAtMs - Date.now()) / 1000);
        if (remainSec <= 0) {
            el.countdown.textContent = '검사 임박';
            return;
        }
        var m = Math.floor(remainSec / 60);
        var s = remainSec % 60;
        el.countdown.textContent = (m > 0 ? m + '분 ' : '') + s + '초';
    }

    function renderHeartbeat(data) {
        var info = STATUS_MAP[data.status] || { cls: 'off-hours', label: String(data.status || '알 수 없음') };
        setPill(info.cls, info.label);

        if (el.lastCheck) el.lastCheck.textContent = fmtTime(data.lastCheckTime);
        if (el.todayCount) el.todayCount.textContent = fmtInt(data.todayCheckCount);
        if (el.minScore) el.minScore.textContent = fmtInt(data.minScore);

        if (el.scanCount) el.scanCount.textContent = fmtInt(data.lastScanCount);
        if (el.scanMetric) {
            // 스캔 0 & 장중(RUNNING/STALE) = 이번 장애의 핵심 신호 → 경고 강조.
            var zeroScanInMarket = (data.lastScanCount === 0) && (data.status === 'RUNNING' || data.status === 'STALE');
            el.scanMetric.classList.toggle('warn', zeroScanInMarket);
        }

        // 카운트다운: backend nextCheckInSeconds(장외면 null) 기준.
        if (typeof data.nextCheckInSeconds === 'number') {
            nextCheckAtMs = Date.now() + data.nextCheckInSeconds * 1000;
        } else {
            nextCheckAtMs = null;
        }
        tickCountdown();
    }

    function renderFunnel(funnel) {
        if (!el.funnel) return;
        funnel = funnel || {};
        el.funnel.innerHTML = FUNNEL_STAGES.map(function (st) {
            var count = funnel[st.key];
            var cls = st.cls;
            // 스캔 대상 0 = 후보 공회전(이번 장애). 경고 강조.
            if (st.key === 'scanned' && count === 0) cls += ' zero-scan';
            return '<div class="funnel-step ' + cls + '">' +
                '<div class="funnel-count">' + fmtInt(count) + '</div>' +
                '<div class="funnel-label">' + esc(st.label) + '</div>' +
                '</div>';
        }).join('');
    }

    // 요소별 점수 셀(0이면 흐리게). nearMiss 인라인용.
    function elementCells(item) {
        return ELEMENTS.map(function (e) {
            var v = item[e.key];
            var cls = (v && v > 0) ? 'element-cell has-score' : 'element-cell';
            return '<span class="' + cls + '" title="' + esc(e.label) + '">' + esc(e.label) + ':' + fmtInt(v) + '</span>';
        }).join(' ');
    }

    function renderNearMiss(nearMiss, minScore) {
        if (!el.nearMiss) return;
        nearMiss = nearMiss || [];
        if (nearMiss.length === 0) {
            el.nearMiss.innerHTML = '<li class="empty-state" style="padding: 20px;">미달 종목 데이터가 없습니다.</li>';
            return;
        }
        el.nearMiss.innerHTML = nearMiss.map(function (m) {
            var shortBy = (typeof minScore === 'number' && typeof m.totalScore === 'number')
                ? Math.max(0, minScore - m.totalScore) : null;
            var badge = (shortBy != null && shortBy > 0)
                ? '<span class="badge fail">부족 ' + shortBy + '</span>' : '';
            return '<li>' +
                '<strong>' + esc(m.name) + '</strong> ' +
                '<span class="stock-code">(' + esc(m.code) + ')</span> ' +
                badge +
                ' <span class="element-cell">총점 ' + fmtInt(m.totalScore) + '/' + fmtInt(minScore) + '</span>' +
                '<div class="shortfall-reason">' + elementCells(m) +
                (m.reason ? ' · ' + esc(m.reason) : '') + '</div>' +
                '</li>';
        }).join('');
    }

    function renderLeaderboard(rows) {
        if (!el.leaderboardBody) return;
        rows = rows || [];
        if (rows.length === 0) {
            el.leaderboardBody.innerHTML = '<tr><td colspan="12" class="empty-state">점수 데이터가 없습니다.</td></tr>';
            return;
        }
        el.leaderboardBody.innerHTML = rows.map(function (r, i) {
            var badge = r.passed
                ? '<span class="badge pass">통과</span>'
                : '<span class="badge fail">부족 ' + fmtInt(r.shortBy) + '</span>';
            var els = ELEMENTS.map(function (e) {
                var v = r[e.key];
                var cls = (v && v > 0) ? 'element-cell has-score' : 'element-cell';
                return '<td class="' + cls + '">' + fmtInt(v) + '</td>';
            }).join('');
            return '<tr>' +
                '<td>' + (i + 1) + '</td>' +
                '<td class="stock-cell"><span class="stock-name">' + esc(r.name) + '</span>' +
                '<span class="stock-code">(' + esc(r.code) + ')</span></td>' +
                '<td class="total-score">' + fmtInt(r.totalScore) + '</td>' +
                '<td>' + badge + '</td>' +
                '<td>' + fmtInt(r.canSlimScore) + '</td>' +
                '<td>' + fmtInt(r.cupScore) + '</td>' +
                els +
                '</tr>';
        }).join('');
    }

    function renderSignals(signals) {
        if (!el.signalsBody) return;
        signals = signals || [];
        if (el.signalsWrap) el.signalsWrap.style.display = signals.length ? 'block' : 'none';
        if (el.signalsEmpty) el.signalsEmpty.style.display = signals.length ? 'none' : 'block';
        el.signalsBody.innerHTML = signals.map(function (s) {
            var st = EXEC_STATUS_MAP[s.status] || { cls: 'blocked', label: String(s.status || '-') };
            return '<tr>' +
                '<td><span class="stock-name">' + esc(s.name) + '</span> ' +
                '<span class="stock-code">(' + esc(s.code) + ')</span></td>' +
                '<td>' + fmtMoney(s.price) + '</td>' +
                '<td>' + fmtInt(s.canSlimScore) + '</td>' +
                '<td>' + fmtInt(s.cupScore) + '</td>' +
                '<td style="font-weight:bold;">' + fmtInt(s.totalScore) + '</td>' +
                '<td><span class="exec-status ' + st.cls + '">' + esc(st.label) + '</span>' +
                (s.detail ? '<div class="shortfall-reason">' + esc(s.detail) + '</div>' : '') + '</td>' +
                '</tr>';
        }).join('');
    }

    function render(data) {
        clearPollError();
        renderHeartbeat(data);
        renderFunnel(data.funnel);
        renderNearMiss(data.nearMiss, data.minScore);
        renderLeaderboard(data.leaderboard);
        renderSignals(data.signals);
    }

    function poll() {
        if (!STATUS_URL) return;
        fetch(STATUS_URL, { headers: { 'Accept': 'application/json' } })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(render)
            .catch(function (err) {
                showPollError('모니터링 상태를 불러오지 못했습니다: ' + err.message +
                    ' (' + new Date().toLocaleTimeString('ko-KR', { hour12: false }) + ')');
            });
    }

    poll();
    setInterval(poll, POLL_INTERVAL_MS);
    setInterval(tickCountdown, COUNTDOWN_TICK_MS);
})();
