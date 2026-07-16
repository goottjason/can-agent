package com.canagent.service;

import com.canagent.MockDataFactory;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.port.BrokerPort;
import com.canagent.port.dto.BrokerBalance;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.StockRepository;
import com.canagent.repository.TradeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("실계좌 동기화(reconcile) 단위테스트")
class PortfolioReconcileServiceTest {

    @Mock
    private PortfolioRepository portfolioRepository;
    @Mock
    private TradeRepository tradeRepository;
    @Mock
    private BrokerPort brokerPort;
    @Mock
    private StockRepository stockRepository;

    @InjectMocks
    private PortfolioService portfolioService;

    private BrokerBalance.Holding holding(String symbol, String name, String qty, String avg) {
        return new BrokerBalance.Holding(symbol, name, new BigDecimal(qty),
                new BigDecimal(avg), new BigDecimal(qty).multiply(new BigDecimal(avg)));
    }

    private BrokerBalance balance(BrokerBalance.Holding... holdings) {
        return new BrokerBalance(true, new BigDecimal("100"), new BigDecimal("100"),
                List.of(holdings), "TEST-ACCT", null);
    }

    @Test
    @DisplayName("(d) getBalance 실패면 아무 변경도 하지 않는다 — 최우선 가드")
    void reconcile_balanceFailure_makesNoChanges() {
        when(brokerPort.getBalance()).thenReturn(BrokerBalance.failure("토큰 발급 실패"));

        PortfolioService.ReconcileSummary summary = portfolioService.reconcileFromBroker();

        assertThat(summary.reconciled()).isFalse();
        assertThat(summary.message()).contains("토큰 발급 실패");
        // 어떤 조회·저장도 일어나지 않아야 한다 (오삭제 방지)
        verify(portfolioRepository, never()).findByActiveTrue();
        verify(portfolioRepository, never()).save(any());
        verify(stockRepository, never()).findByCode(anyString());
    }

    @Test
    @DisplayName("(a) broker 0보유 → DB active 전부 deactivate")
    void reconcile_brokerEmpty_deactivatesAllActive() {
        Stock samsung = MockDataFactory.createSamsungStock();
        Stock naver = MockDataFactory.createNaverStock();
        Portfolio p1 = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        Portfolio p2 = MockDataFactory.createPortfolio(naver, 5, new BigDecimal("300000"));
        when(brokerPort.getBalance()).thenReturn(balance());
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(p1, p2));

        PortfolioService.ReconcileSummary summary = portfolioService.reconcileFromBroker();

        assertThat(summary.reconciled()).isTrue();
        assertThat(summary.brokerHoldings()).isEqualTo(0);
        assertThat(summary.deactivated()).isEqualTo(2);
        assertThat(summary.updated()).isEqualTo(0);
        assertThat(summary.added()).isEqualTo(0);
        assertThat(p1.isActive()).isFalse();
        assertThat(p2.isActive()).isFalse();
        verify(portfolioRepository).save(p1);
        verify(portfolioRepository).save(p2);
    }

    @Test
    @DisplayName("(b) broker 일부 보유 → 없는 것만 deactivate, 있는 것은 유지")
    void reconcile_partialHoldings_deactivatesMissingOnly() {
        Stock samsung = MockDataFactory.createSamsungStock();
        Stock naver = MockDataFactory.createNaverStock();
        Portfolio pSamsung = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        Portfolio pNaver = MockDataFactory.createPortfolio(naver, 5, new BigDecimal("300000"));
        // broker에는 삼성만 있고 수량/평단 동일 → 삼성 유지, 네이버 deactivate
        when(brokerPort.getBalance()).thenReturn(balance(holding("005930", "삼성전자", "10", "70000")));
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(pSamsung, pNaver));

        PortfolioService.ReconcileSummary summary = portfolioService.reconcileFromBroker();

        assertThat(summary.reconciled()).isTrue();
        assertThat(summary.brokerHoldings()).isEqualTo(1);
        assertThat(summary.deactivated()).isEqualTo(1);
        assertThat(summary.updated()).isEqualTo(0);
        assertThat(summary.added()).isEqualTo(0);
        assertThat(pSamsung.isActive()).isTrue();
        assertThat(pNaver.isActive()).isFalse();
        verify(portfolioRepository).save(pNaver);
        verify(portfolioRepository, never()).save(pSamsung);
    }

    @Test
    @DisplayName("(e) broker 수량/평단 다름 → broker 값으로 갱신")
    void reconcile_quantityMismatch_updatesToBrokerValues() {
        Stock samsung = MockDataFactory.createSamsungStock();
        Portfolio pSamsung = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        // broker 수량 7, 평단 72000 → 갱신
        when(brokerPort.getBalance()).thenReturn(balance(holding("005930", "삼성전자", "7", "72000")));
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(pSamsung));

        PortfolioService.ReconcileSummary summary = portfolioService.reconcileFromBroker();

        assertThat(summary.reconciled()).isTrue();
        assertThat(summary.updated()).isEqualTo(1);
        assertThat(summary.deactivated()).isEqualTo(0);
        assertThat(pSamsung.isActive()).isTrue();
        assertThat(pSamsung.getQuantity()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(pSamsung.getAverageBuyPrice()).isEqualByComparingTo(new BigDecimal("72000"));
        verify(portfolioRepository).save(pSamsung);
    }

    @Test
    @DisplayName("(c) broker에 있는데 DB active에 없는 종목 → 종목 존재 시 신규 Portfolio 추가")
    void reconcile_newBrokerHolding_addsWhenStockExists() {
        Stock naver = MockDataFactory.createNaverStock();
        when(brokerPort.getBalance()).thenReturn(balance(holding("035420", "네이버", "3", "300000")));
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of());
        when(stockRepository.findByCode("035420")).thenReturn(Optional.of(naver));

        PortfolioService.ReconcileSummary summary = portfolioService.reconcileFromBroker();

        assertThat(summary.reconciled()).isTrue();
        assertThat(summary.added()).isEqualTo(1);
        assertThat(summary.deactivated()).isEqualTo(0);
        assertThat(summary.skipped()).isEmpty();
        verify(portfolioRepository).save(any(Portfolio.class));
    }

    @Test
    @DisplayName("(c') broker에 있는데 종목이 DB에 없음 → skip + 경고")
    void reconcile_newBrokerHolding_skipsWhenStockUnknown() {
        when(brokerPort.getBalance()).thenReturn(balance(holding("999999", "미상종목", "3", "1000")));
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of());
        when(stockRepository.findByCode("999999")).thenReturn(Optional.empty());

        PortfolioService.ReconcileSummary summary = portfolioService.reconcileFromBroker();

        assertThat(summary.reconciled()).isTrue();
        assertThat(summary.added()).isEqualTo(0);
        assertThat(summary.skipped()).containsExactly("999999");
        verify(portfolioRepository, never()).save(any());
    }
}
