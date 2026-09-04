package com.canagent.worker;

import com.canagent.service.lottery.LotteryPurchaseService;
import com.canagent.service.lottery.LotteryResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("복권 스케줄러 위임 단위테스트")
class LotterySchedulerTest {

    @Test
    @DisplayName("각 크론 메서드가 해당 서비스에 위임한다")
    void delegates() {
        LotteryPurchaseService purchase = mock(LotteryPurchaseService.class);
        LotteryResultService result = mock(LotteryResultService.class);
        LotteryScheduler scheduler = new LotteryScheduler(purchase, result);

        scheduler.buy();
        scheduler.retryBuy();
        scheduler.reportUnpurchased();
        scheduler.checkWin720();
        scheduler.checkLotto();

        verify(purchase, times(2)).buyWeekly();   // 최초 구매 + 재시도 — 같은 멱등 연산
        verify(purchase).reportUnpurchased();
        verify(result).checkWin720();
        verify(result).checkLotto();
    }

    @Test
    @DisplayName("재시도 크론은 연금 판매마감(목 17시) 전 화·수·목 시간대로 설정된다")
    void retryCronRunsBeforeWin720SalesClose() throws Exception {
        Method m = LotteryScheduler.class.getMethod("retryBuy");
        String cron = m.getAnnotation(Scheduled.class).cron();

        assertThat(cron).isEqualTo("${lottery.buy-retry-cron:0 0 11,15 * * TUE,WED,THU}");
        assertThat(m.getAnnotation(Scheduled.class).zone()).isEqualTo("Asia/Seoul");
    }
}
