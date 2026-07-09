package com.canagent.worker;

import com.canagent.port.FinancialsPort;
import com.canagent.service.KrxDataSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
@ConditionalOnProperty(name = "trading.scheduler.enabled", havingValue = "true")
public class DataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataSyncScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final KrxDataSyncService krxDataSyncService;
    // P4: 재무 의존을 구체 DartDataSyncService → FinancialsPort로 전환(P1 디커플 완성).
    // 유일 구현은 EdgarFinancialsAdapter. KRX/price 경로(krxDataSyncService)는 P5 소관이라 손대지 않는다.
    private final FinancialsPort financialsPort;

    public DataSyncScheduler(KrxDataSyncService krxDataSyncService,
                             FinancialsPort financialsPort) {
        this.krxDataSyncService = krxDataSyncService;
        this.financialsPort = financialsPort;
    }

    @Scheduled(cron = "${trading.scheduler.sync-cron:0 30 15 * * MON-FRI}", zone = "Asia/Seoul")
    public void syncDailyData() {
        log.info("===== 일일 데이터 동기화 시작 =====");

        LocalDate today = LocalDate.now(KST);
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

    @Scheduled(cron = "${trading.scheduler.quarterly-cron:0 0 10 1 1,4,7,10 *}", zone = "Asia/Seoul")
    public void syncQuarterlyFinancials() {
        log.info("===== 분기 재무제표 동기화 시작 =====");

        LocalDate today = LocalDate.now(KST);
        String year = String.valueOf(today.getYear());
        String quarter = getQuarter(today);

        try {
            int count = financialsPort.syncAllActiveStocks(year, quarter);
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

    public void runBulkPriceSync(int tradingDays) {
        log.info("벌크 주가 동기화 시작: {} 거래일", tradingDays);
        try {
            int count = krxDataSyncService.bulkSyncAllActiveStocks(tradingDays);
            log.info("벌크 주가 동기화 완료: {}건 저장", count);
        } catch (Exception e) {
            log.error("벌크 주가 동기화 실패: {}", e.getMessage());
        }
    }

    public void runBulkFinancialSync(int quarters) {
        log.info("벌크 재무 동기화 시작: {}분기", quarters);
        try {
            // EDGAR companyfacts는 1회 호출로 전체 히스토리를 반환하므로 분기 루프가 불필요하다.
            // quarters를 "몇 년치 하한"으로 환산해 힌트로 전달(quarter 파라미터는 EDGAR가 무시).
            int yearsBack = Math.max(1, (quarters + 3) / 4);
            String startYear = String.valueOf(LocalDate.now(KST).getYear() - yearsBack);
            int count = financialsPort.syncAllActiveStocks(startYear, null);
            log.info("벌크 재무 동기화 완료: {}건 저장 (startYear={})", count, startYear);
        } catch (Exception e) {
            log.error("벌크 재무 동기화 실패: {}", e.getMessage());
        }
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
