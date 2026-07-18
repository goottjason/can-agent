package com.canagent.service.lottery;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.SidecarError;
import com.canagent.port.dto.SidecarResults;
import com.canagent.port.dto.TicketResult;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.notification.NotificationServiceRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LotteryResultService {

    private static final Logger log = LoggerFactory.getLogger(LotteryResultService.class);

    private final LotteryTicketRepository repository;
    private final LotterySidecarPort sidecar;
    private final NotificationServiceRouter router;

    public LotteryResultService(LotteryTicketRepository repository, LotterySidecarPort sidecar,
                                NotificationServiceRouter router) {
        this.repository = repository;
        this.sidecar = sidecar;
        this.router = router;
    }

    /** 로또 미확인 티켓 당첨 결과 일괄 확인(사이드카 MY 당첨내역 기반). */
    public void checkLotto() {
        check(GameType.LOTTO645, "🎯 로또 당첨확인", "로또", "• 로또 ");
    }

    /** 연금복권 미확인 티켓 당첨 결과 일괄 확인(사이드카 MY 당첨내역 기반). */
    public void checkWin720() {
        check(GameType.WIN720, "🎯 연금복권 당첨확인", "연금", "• 연금 ");
    }

    /**
     * 사이드카 결과를 1회 조회해 해당 게임의 미확인 티켓을 (roundNo)로 매칭·판정한다.
     * WIN→당첨(rank 1, 금액 라벨), LOSE→낙첨(rank 0), PENDING/매칭없음→저장 안 함(다음 크론 재시도).
     * 발표날 크론이므로 결과 유무와 무관하게 항상 텔레그램 1건을 발송한다.
     */
    private void check(GameType gameType, String header, String logLabel, String linePrefix) {
        SidecarResults sr = sidecar.checkResults();
        if (!sr.ok()) {
            String reason = sr.errors().stream().map(SidecarError::reason).collect(Collectors.joining("; "));
            log.error("{} 당첨확인 실패: {}", logLabel, reason);
            router.sendText(header + " 실패: " + (reason.isBlank() ? "사이드카 오류" : reason));
            return;
        }

        List<LotteryTicket> pending = repository.findByGameTypeAndResultCheckedFalse(gameType);
        // (roundNo) → 결과. 같은 게임·회차 결과가 여럿이면 마지막 것을 사용(사이드카는 게임당 회차 1건 기대).
        Map<Integer, TicketResult> byRound = sr.results().stream()
                .filter(r -> r.gameType() == gameType)
                .collect(Collectors.toMap(TicketResult::roundNo, r -> r, (a, b) -> b));

        StringBuilder summary = new StringBuilder();
        for (LotteryTicket t : pending) {
            try {
                TicketResult r = byRound.get(t.getRoundNo());
                if (r == null || "PENDING".equals(r.status())) continue;  // 미확정 — 저장 안 함
                if ("WIN".equals(r.status())) {
                    t.applyResult(1, "당첨 (₩" + String.format("%,d", r.winAmount()) + ")");
                } else {  // LOSE
                    t.applyResult(0, "낙첨");
                }
                repository.save(t);
                summary.append(linePrefix).append(t.getRoundNo()).append("회 [").append(t.getNumbers())
                       .append("] → ").append(t.getPrizeLabel()).append("\n");
            } catch (Exception e) {
                log.error("복권 당첨확인 처리 실패 (id={}, numbers={}): {}", t.getId(), t.getNumbers(), e.getMessage());
            }
        }

        if (summary.length() > 0) {
            router.sendText(header + "\n" + summary);
        } else if (pending.isEmpty()) {
            log.info("{} 당첨확인: 확인할 티켓 없음", logLabel);
            router.sendText(header + ": 확인할 티켓이 없습니다.");
        } else {
            log.info("{} 당첨확인: 대상 {}건이나 아직 추첨 결과 없음(미추첨)", logLabel, pending.size());
            router.sendText(header + ": 아직 추첨 결과가 없습니다(미추첨).");
        }
    }
}
