package com.canagent.web;

import com.canagent.MockDataFactory;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.StockRepository;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("대시보드 E2E 테스트")
@Transactional
class DashboardE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockPriceRepository stockPriceRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @BeforeEach
    void setUp() {
        Stock stock = MockDataFactory.createSamsungStock();
        stockRepository.save(stock);

        StockPrice price = MockDataFactory.createRisingPrice(
                stock, LocalDate.now(), new BigDecimal("75000"));
        stockPriceRepository.save(price);

        Portfolio portfolio = MockDataFactory.createPortfolio(stock, 10, new BigDecimal("70000"));
        portfolioRepository.save(portfolio);
    }

    @Test
    @DisplayName("대시보드 페이지가 정상적으로 로드된다")
    void dashboard_pageLoads() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attributeExists("portfolios"))
                .andExpect(model().attributeExists("recentTrades"))
                .andExpect(model().attributeExists("totalInvestment"))
                .andExpect(model().attributeExists("totalCurrentValue"))
                .andExpect(model().attributeExists("totalProfit"))
                .andExpect(model().attributeExists("totalProfitRate"))
                .andExpect(model().attributeExists("portfolioCount"))
                .andExpect(model().attributeExists("buyCount"))
                .andExpect(model().attributeExists("sellCount"));
    }

    @Test
    @DisplayName("대시보드에 포트폴리오 정보가 표시된다")
    void dashboard_showsPortfolioInfo() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("portfolioCount", 1));
    }

    @Test
    @DisplayName("수동 검사 실행 후 리다이렉트된다")
    void runManualCheck_redirects() throws Exception {
        mockMvc.perform(post("/trade/run"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("포트폴리오가 없을 때도 대시보드가 로드된다")
    void dashboard_noPortfolios_loads() throws Exception {
        portfolioRepository.deleteAll();

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("portfolioCount", 0));
    }

    // ========== #6(2026-07-11): 렌더 본문 통화·시장 정합 어서션(가짜통과 방지) ==========

    @Test
    @DisplayName("대시보드 렌더 본문은 달러 통화($)를 쓰고 원화(원) 표기가 없다 — 미국 대전환 정합")
    void dashboard_rendersDollarNotWon() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                // 자산/평가/매입 카드가 '$' 접두로 렌더된다(달러 표기).
                .andExpect(content().string(containsString("$")))
                // 원화 통화 표기가 남아있지 않다(국내 잔재 회귀 방지 — 사이드바 포함 렌더 본문 전체).
                .andExpect(content().string(not(containsString("원"))));
    }

    @Test
    @DisplayName("종목목록 렌더 본문은 미국 거래소(NYSE)를 쓰고 국내 시장(KOSPI/KOSDAQ)이 없다")
    void stockList_rendersUsExchangeNotKorean() throws Exception {
        // setUp의 삼성전자(KOSPI)를 비우고 미국 거래소 종목만 남겨 시장 태그 본문을 검증한다.
        portfolioRepository.deleteAll();
        stockPriceRepository.deleteAll();
        stockRepository.deleteAll();
        stockRepository.save(new Stock("AAPL", "Apple Inc.", "NYSE", "Technology"));

        mockMvc.perform(get("/stocks"))
                .andExpect(status().isOk())
                .andExpect(view().name("stock-list"))
                // 시장 태그가 NYSE로 렌더된다.
                .andExpect(content().string(containsString("NYSE")))
                // 국내 시장 표기가 본문에 없다(회귀 방지).
                .andExpect(content().string(not(containsString("KOSPI"))))
                .andExpect(content().string(not(containsString("KOSDAQ"))));
    }
}
