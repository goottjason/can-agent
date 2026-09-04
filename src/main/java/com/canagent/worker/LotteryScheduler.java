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

    // 구매 재시도: 화·수·목 11/15시 KST. buyWeekly()는 이번 주 미구매 게임만 요청하므로 멱등이다.
    // 연금 판매마감(목 17시경) 전까지만 시도 — 마감 후 구매는 다음 회차가 되어 이번 주 복구가 아니다.
    @Scheduled(cron = "${lottery.buy-retry-cron:0 0 11,15 * * TUE,WED,THU}", zone = "Asia/Seoul")
    public void retryBuy() {
        log.info("===== 복권 구매 재시도 =====");
        purchaseService.buyWeekly();
    }

    // 재시도 창 종료 직후 결말 통보(구매 실패로 끝났으면 확실히 알린다)
    @Scheduled(cron = "${lottery.buy-deadline-cron:0 30 15 * * THU}", zone = "Asia/Seoul")
    public void reportUnpurchased() {
        log.info("===== 복권 주간 구매 결말 확인 =====");
        purchaseService.reportUnpurchased();
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
