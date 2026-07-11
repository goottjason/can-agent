package com.canagent.service.edgar;

import com.canagent.domain.stock.Stock;
import com.canagent.repository.StockRepository;
import com.canagent.service.StockService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #3 미국 유니버스 적재 통합테스트.
 * 실제 {@code src/main/resources/company_tickers.json}(대형주 100종목)을 클래스패스에서
 * {@link SecTickerUniverseLoader#loadFromClasspath()}로 적재하고,
 * exchange→market 매핑·식별필드·P8 카운트 정합(NYSE+NASDAQ=100)을 검증한다.
 * test 프로필에선 DotenvConfig(@Profile("!test")) 비활성 — 영향 없음.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("US 유니버스 적재 통합테스트 (실 company_tickers.json 100종목)")
class SecTickerUniverseIntegrationTest {

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("실 company_tickers.json 로드 → 100종목 upsert, exchange/market/cik/ticker 채움, NYSE+NASDAQ=100")
    void loadsRealUniverseAndFillsIdentifiers() {
        SecTickerUniverseLoader loader = new SecTickerUniverseLoader(stockRepository);
        StockService stockService = new StockService(stockRepository);

        int upserted = loader.loadFromClasspath();

        // upsert 건수 = 파일의 100종목
        assertThat(upserted).isEqualTo(100);

        // 저장분이 카운트 쿼리에 반영되도록 flush + clear
        entityManager.flush();
        entityManager.clear();

        // P8 카운트 정합: 거래소별 합이 100 (NYSE 64 + NASDAQ 36)
        long nyse = stockService.getStockCountByMarket("NYSE");
        long nasdaq = stockService.getStockCountByMarket("NASDAQ");
        assertThat(nyse).isEqualTo(64);
        assertThat(nasdaq).isEqualTo(36);
        assertThat(nyse + nasdaq).isEqualTo(100);
        assertThat(stockService.getActiveStockCount()).isEqualTo(100);

        // 대표 종목: AAPL(NASDAQ) — exchange→market 매핑·식별필드 채움 확인
        Stock apple = stockRepository.findByTicker("AAPL").orElseThrow();
        assertThat(apple.getMarket()).isEqualTo("NASDAQ");
        assertThat(apple.getExchange()).isEqualTo("NASDAQ");
        assertThat(apple.getCik()).isEqualTo("320193");
        assertThat(apple.getCurrency()).isEqualTo("USD");
        assertThat(apple.getCode()).isEqualTo("AAPL");

        // 모든 저장 종목이 ticker/cik/market을 갖는지(누락 upsert 없음) 확인
        long withIdentifiers = stockRepository.findAll().stream()
                .filter(s -> s.getTicker() != null && s.getCik() != null && s.getMarket() != null)
                .count();
        assertThat(withIdentifiers).isEqualTo(100);
    }
}
