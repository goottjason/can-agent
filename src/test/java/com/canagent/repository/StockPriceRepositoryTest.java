package com.canagent.repository;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("StockPriceRepository 후보 종목 조회 단위테스트")
class StockPriceRepositoryTest {

    @Autowired
    private StockPriceRepository stockPriceRepository;

    @Autowired
    private StockRepository stockRepository;

    private Stock saveStock(String code, String name) {
        return stockRepository.save(new Stock(code, name, "KOSPI", "IT"));
    }

    private void savePrice(Stock stock, LocalDate date, long volume, String changeRate) {
        StockPrice sp = new StockPrice(stock, date,
                new BigDecimal("1000"), new BigDecimal("1100"), new BigDecimal("900"),
                new BigDecimal("1050"), volume);
        sp.setChangeRate(new BigDecimal(changeRate));
        stockPriceRepository.save(sp);
    }

    @Test
    @DisplayName("오늘 이전의 최신 거래일을 반환한다")
    void findLatestTradeDateBefore_returnsMostRecentCompletedDay() {
        Stock stock = saveStock("000001", "테스트");
        savePrice(stock, LocalDate.of(2026, 7, 2), 100, "1.0");
        savePrice(stock, LocalDate.of(2026, 7, 6), 200, "2.0");
        // 오늘 날짜(장중 미완성) — 제외되어야 함
        savePrice(stock, LocalDate.of(2026, 7, 7), 300, "3.0");

        Optional<LocalDate> base = stockPriceRepository.findLatestTradeDateBefore(LocalDate.of(2026, 7, 7));

        assertThat(base).contains(LocalDate.of(2026, 7, 6));
    }

    @Test
    @DisplayName("데이터가 없으면 빈 Optional을 반환한다")
    void findLatestTradeDateBefore_empty() {
        Optional<LocalDate> base = stockPriceRepository.findLatestTradeDateBefore(LocalDate.of(2026, 7, 7));
        assertThat(base).isEmpty();
    }

    @Test
    @DisplayName("거래량 상위 조회는 Stock을 즉시 로딩하고 limit을 적용한다")
    void findTopByVolumeOnDate_fetchesStockAndLimits() {
        LocalDate date = LocalDate.of(2026, 7, 6);
        for (int i = 0; i < 3; i++) {
            Stock s = saveStock("00010" + i, "종목" + i);
            savePrice(s, date, 100L * (i + 1), "1.0");
        }

        List<StockPrice> top2 = stockPriceRepository.findTopByVolumeOnDate(date, PageRequest.of(0, 2));

        assertThat(top2).hasSize(2);
        // 거래량 내림차순
        assertThat(top2.get(0).getVolume()).isGreaterThanOrEqualTo(top2.get(1).getVolume());
        // JOIN FETCH로 세션 밖에서도 Stock 접근 가능해야 함 (LazyInit 방지)
        assertThat(top2.get(0).getStock().getName()).isNotBlank();
    }

    @Test
    @DisplayName("변동률 상위 조회는 절대값 기준 내림차순으로 정렬한다")
    void findTopByChangeRateOnDate_ordersByAbsDesc() {
        LocalDate date = LocalDate.of(2026, 7, 6);
        Stock a = saveStock("000201", "A");
        Stock b = saveStock("000202", "B");
        Stock c = saveStock("000203", "C");
        savePrice(a, date, 100, "3.0");
        savePrice(b, date, 100, "-9.5");
        savePrice(c, date, 100, "1.0");

        List<StockPrice> top = stockPriceRepository.findTopByChangeRateOnDate(date, PageRequest.of(0, 10));

        assertThat(top).hasSize(3);
        assertThat(top.get(0).getStock().getCode()).isEqualTo("000202"); // |−9.5| 최대
    }
}
