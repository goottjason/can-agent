package com.canagent.worker;

import com.canagent.service.lottery.LotteryPurchaseService;
import com.canagent.service.lottery.LotteryResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        scheduler.checkWin720();
        scheduler.checkLotto();

        verify(purchase).buyWeekly();
        verify(result).checkWin720();
        verify(result).checkLotto();
    }
}
