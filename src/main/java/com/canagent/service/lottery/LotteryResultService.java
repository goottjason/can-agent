package com.canagent.service.lottery;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.lottery.dto.LottoDraw;
import com.canagent.service.lottery.dto.Win720Draw;
import com.canagent.service.notification.NotificationServiceRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LotteryResultService {

    private static final Logger log = LoggerFactory.getLogger(LotteryResultService.class);

    private final LotteryTicketRepository repository;
    private final LottoResultClient lottoClient;
    private final Win720ResultClient win720Client;
    private final NotificationServiceRouter router;

    public LotteryResultService(LotteryTicketRepository repository, LottoResultClient lottoClient,
                                Win720ResultClient win720Client, NotificationServiceRouter router) {
        this.repository = repository;
        this.lottoClient = lottoClient;
        this.win720Client = win720Client;
        this.router = router;
    }

    /** 로또 미확인 티켓 당첨 결과 일괄 확인. 미추첨 회차는 건너뜀. */
    public void checkLotto() {
        List<LotteryTicket> pending = repository.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645);
        Map<Integer, LottoDraw> cache = new HashMap<>();
        StringBuilder summary = new StringBuilder();

        for (LotteryTicket t : pending) {
            LottoDraw draw = cache.computeIfAbsent(t.getRoundNo(), lottoClient::getWinningNumbers);
            if (!draw.success()) continue;   // 아직 미추첨 — 저장하지 않음
            List<Integer> nums = Arrays.stream(t.getNumbers().split(",")).map(Integer::parseInt).toList();
            int rank = LotteryRankCalculator.lottoRank(nums, draw);
            String label = LotteryPrizeFormatter.lotto(rank, draw);
            t.applyResult(rank, label);
            repository.save(t);
            summary.append("• 로또 ").append(t.getRoundNo()).append("회 [").append(t.getNumbers())
                   .append("] → ").append(label).append("\n");
        }
        if (summary.length() > 0) {
            router.sendText("🎯 로또 당첨확인\n" + summary);
        } else {
            log.info("로또 당첨확인: 대상 없음");
        }
    }

    /** 연금복권 미확인 티켓 당첨 결과 일괄 확인. 미추첨 회차는 건너뜀. */
    public void checkWin720() {
        List<LotteryTicket> pending = repository.findByGameTypeAndResultCheckedFalse(GameType.WIN720);
        Map<Integer, Win720Draw> cache = new HashMap<>();
        StringBuilder summary = new StringBuilder();

        for (LotteryTicket t : pending) {
            Win720Draw draw = cache.computeIfAbsent(t.getRoundNo(), win720Client::getWinningNumbers);
            if (!draw.success()) continue;   // 아직 미추첨 — 저장하지 않음
            String[] parts = t.getNumbers().split(":");   // "조:6자리" 형식 파싱
            int jo = Integer.parseInt(parts[0]);
            String digits = parts[1];
            int rank = LotteryRankCalculator.win720Rank(jo, digits, draw);
            String label = LotteryPrizeFormatter.win720(rank);
            t.applyResult(rank, label);
            repository.save(t);
            summary.append("• 연금 ").append(t.getRoundNo()).append("회 [").append(t.getNumbers())
                   .append("] → ").append(label).append("\n");
        }
        if (summary.length() > 0) {
            router.sendText("🎯 연금복권 당첨확인\n" + summary);
        } else {
            log.info("연금 당첨확인: 대상 없음");
        }
    }
}
