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
}
