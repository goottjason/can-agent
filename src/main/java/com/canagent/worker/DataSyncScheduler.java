package com.canagent.worker;

import com.canagent.port.FinancialsPort;
import com.canagent.port.MarketDataPort;
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
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // P5: price 동기화 의존을 구체 KrxDataSyncService → MarketDataPort로 전환(P1 디커플 완성).
    // 유일 구현은 TossMarketDataAdapter. 구현·모킹 교체가 가능해진다.
    private final MarketDataPort marketDataPort;
    // P4: 재무 의존을 구체 DartDataSyncService → FinancialsPort로 전환. 유일 구현은 EdgarFinancialsAdapter.
    private final FinancialsPort financialsPort;

    public DataSyncScheduler(MarketDataPort marketDataPort,
                             FinancialsPort financialsPort) {
        this.marketDataPort = marketDataPort;
        this.financialsPort = financialsPort;
    }

    // 재무·주가 동기화(E §5): SEC 나이틀리 companyfacts ZIP 재컴파일(~03:00 ET) 이후인 04:00 ET에 실행.
    @Scheduled(cron = "${trading.scheduler.sync-cron:0 0 4 * * MON-FRI}", zone = "America/New_York")
    public void syncDailyData() {
        log.info("===== 일일 데이터 동기화 시작 =====");

        LocalDate today = LocalDate.now(ET);
        if (isWeekend(today)) {
            log.info("주말은 동기화 스킵");
            return;
        }

        try {
            LocalDate startDate = today.minusDays(1);
            LocalDate endDate = today;

            int priceCount = marketDataPort.syncAllActiveStocks(startDate, endDate);
            log.info("주가 데이터 동기화: {}건", priceCount);
        } catch (Exception e) {
            log.error("주가 데이터 동기화 실패: {}", e.getMessage());
        }

        log.info("===== 일일 데이터 동기화 종료 =====");
    }

    // 분기 재무(E §5): SEC 10-Q/10-K 제출주기 기준. 분기 시작월(1·4·7·10) 1일 10:00 ET에 재컴파일.
    @Scheduled(cron = "${trading.scheduler.quarterly-cron:0 0 10 1 1,4,7,10 *}", zone = "America/New_York")
    public void syncQuarterlyFinancials() {
        log.info("===== 분기 재무제표 동기화 시작 =====");

        LocalDate today = LocalDate.now(ET);
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
            // P5: 포트 경유로 전환. 거래일수를 달력 기간으로 환산(주말·휴장 여유 ×1.6)해
            // 백필 시작일을 산출 → 어댑터가 종목별 캔들 페이지네이션으로 채운다.
            LocalDate endDate = LocalDate.now(ET);
            LocalDate startDate = endDate.minusDays((long) (tradingDays * 1.6));
            int count = marketDataPort.syncAllActiveStocks(startDate, endDate);
            log.info("벌크 주가 동기화 완료: {}건 저장 ({}~{})", count, startDate, endDate);
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
            String startYear = String.valueOf(LocalDate.now(ET).getYear() - yearsBack);
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
