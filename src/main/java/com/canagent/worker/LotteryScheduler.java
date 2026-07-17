package com.canagent.worker;

import com.canagent.service.lottery.LotteryPurchaseService;
import com.canagent.service.lottery.LotteryResultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lottery.enabled", havingValue = "true")
public class LotteryScheduler {

    private static final Logger log = LoggerFactory.getLogger(LotteryScheduler.class);

    private final LotteryPurchaseService purchaseService;
    private final LotteryResultService resultService;

    public LotteryScheduler(LotteryPurchaseService purchaseService, LotteryResultService resultService) {
        this.purchaseService = purchaseService;
        this.resultService = resultService;
    }

    // 주간 구매: 매주 화 09:00 KST (두 추첨일 전에 미리)
    @Scheduled(cron = "${lottery.buy-cron:0 0 9 * * TUE}", zone = "Asia/Seoul")
    public void buy() {
        log.info("===== 복권 주간 구매 =====");
        purchaseService.buyWeekly();
    }

    // 연금 추첨: 목 밤 22:00 확인 (추첨 반영 여유 확보)
    @Scheduled(cron = "${lottery.win720-result-cron:0 0 22 * * THU}", zone = "Asia/Seoul")
    public void checkWin720() {
        log.info("===== 연금복권 당첨확인 =====");
        resultService.checkWin720();
    }

    // 로또 추첨: 토 밤 22:00 확인 (추첨 API 반영 여유 확보)
    @Scheduled(cron = "${lottery.lotto-result-cron:0 0 22 * * SAT}", zone = "Asia/Seoul")
    public void checkLotto() {
        log.info("===== 로또 당첨확인 =====");
        resultService.checkLotto();
    }
}
