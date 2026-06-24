package com.canagent.worker;

import com.canagent.service.DartDataSyncService;
import com.canagent.service.KrxDataSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Component
@ConditionalOnProperty(name = "trading.scheduler.enabled", havingValue = "true")
public class DataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataSyncScheduler.class);

    private final KrxDataSyncService krxDataSyncService;
    private final DartDataSyncService dartDataSyncService;

    public DataSyncScheduler(KrxDataSyncService krxDataSyncService,
                             DartDataSyncService dartDataSyncService) {
        this.krxDataSyncService = krxDataSyncService;
        this.dartDataSyncService = dartDataSyncService;
    }

    @Scheduled(cron = "${trading.scheduler.sync-cron:0 30 15 * * MON-FRI}")
    public void syncDailyData() {
        log.info("===== 일일 데이터 동기화 시작 =====");

        LocalDate today = LocalDate.now();
        if (isWeekend(today)) {
            log.info("주말은 동기화 스킵");
            return;
        }

        try {
            LocalDate startDate = today.minusDays(1);
            LocalDate endDate = today;

            int priceCount = krxDataSyncService.syncAllActiveStocks(startDate, endDate);
            log.info("주가 데이터 동기화: {}건", priceCount);
        } catch (Exception e) {
            log.error("주가 데이터 동기화 실패: {}", e.getMessage());
        }

        log.info("===== 일일 데이터 동기화 종료 =====");
    }

    @Scheduled(cron = "${trading.scheduler.quarterly-cron:0 0 10 1 1,4,7,10 *}")
    public void syncQuarterlyFinancials() {
        log.info("===== 분기 재무제표 동기화 시작 =====");

        LocalDate today = LocalDate.now();
        String year = String.valueOf(today.getYear());
        String quarter = getQuarter(today);

        try {
            int count = dartDataSyncService.syncAllActiveStocks(year, quarter);
            log.info("재무제표 동기화: {}건", count);
        } catch (Exception e) {
            log.error("재무제표 동기화 실패: {}", e.getMessage());
        }

        log.info("===== 분기 재무제표 동기화 종료 =====");
    }

    public void runManualSync() {
        log.info("수동 데이터 동기화 시작");
        syncDailyData();
        log.info("수동 데이터 동기화 완료");
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }

    private String getQuarter(LocalDate date) {
        int month = date.getMonthValue();
        if (month <= 3) return "1";
        if (month <= 6) return "2";
        if (month <= 9) return "3";
        return "4";
    }
}
