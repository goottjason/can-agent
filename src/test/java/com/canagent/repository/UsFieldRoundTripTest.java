package com.canagent.repository;

import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.FiscalPeriodType;
import com.canagent.domain.stock.Stock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 additive 스키마 필드 왕복 실증.
 * US 식별필드(Stock)·periodType/currency(FinancialStatement)를 저장→clear→조회로 검증한다.
 * 분석기 로직은 미변경(P4 이관).
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("US additive 필드 저장/조회 왕복 단위테스트")
class UsFieldRoundTripTest {

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private FinancialStatementRepository financialStatementRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Stock의 US 식별필드(ticker/cik/exchange/currency/sicCode)를 setter로 채우고 왕복한다")
    void stockUsIdentifiers_roundTrip() {
        Stock stock = new Stock("AAPL", "Apple Inc.", "NASDAQ", "Technology");
        stock.setTicker("AAPL");
        stock.setCik("0000320193");
        stock.setExchange("NASDAQ");
        stock.setCurrency("USD");
        stock.setSicCode("3571");
        Long id = stockRepository.save(stock).getId();

        entityManager.flush();
        entityManager.clear();

        Stock found = stockRepository.findById(id).orElseThrow();
        assertThat(found.getTicker()).isEqualTo("AAPL");
        assertThat(found.getCik()).isEqualTo("0000320193");
        assertThat(found.getExchange()).isEqualTo("NASDAQ");
        assertThat(found.getCurrency()).isEqualTo("USD");
        assertThat(found.getSicCode()).isEqualTo("3571");
        // 기존 KR 필드 무변경 확인
        assertThat(found.getCode()).isEqualTo("AAPL");
        assertThat(found.isActive()).isTrue();
    }

    @Test
    @DisplayName("KR 종목은 US 식별필드가 null로 유지된다(기존 동작 불변)")
    void krStock_usFieldsRemainNull() {
        Long id = stockRepository.save(new Stock("005930", "삼성전자", "KOSPI", "반도체")).getId();

        entityManager.flush();
        entityManager.clear();

        Stock found = stockRepository.findById(id).orElseThrow();
        assertThat(found.getTicker()).isNull();
        assertThat(found.getCik()).isNull();
        assertThat(found.getExchange()).isNull();
        assertThat(found.getCurrency()).isNull();
        assertThat(found.getSicCode()).isNull();
    }

    @Test
    @DisplayName("FinancialStatement에 periodType=ANNUAL·currency를 setter로 채우고 왕복한다(fiscalQuarter=null 허용)")
    void financialStatementAnnual_roundTrip() {
        Stock stock = stockRepository.save(new Stock("AAPL", "Apple Inc.", "NASDAQ", "Technology"));

        // 연간행: fiscalQuarter=null, periodType=ANNUAL
        FinancialStatement fs = new FinancialStatement(stock, 2024, null, LocalDate.of(2024, 12, 31));
        fs.setPeriodType(FiscalPeriodType.ANNUAL);
        fs.setCurrency("USD");
        // 미국 EPS 소수 정밀(scale 4) 및 고부채(precision 12) 왕복 확인
        fs.updateFinancials(
                new BigDecimal("391035000000.00"),
                new BigDecimal("123216000000.00"),
                new BigDecimal("93736000000.00"),
                new BigDecimal("6.1100"),
                new BigDecimal("150.07"),
                new BigDecimal("209.05")
        );
        Long id = financialStatementRepository.save(fs).getId();

        entityManager.flush();
        entityManager.clear();

        FinancialStatement found = financialStatementRepository.findById(id).orElseThrow();
        assertThat(found.getPeriodType()).isEqualTo(FiscalPeriodType.ANNUAL);
        assertThat(found.getCurrency()).isEqualTo("USD");
        assertThat(found.getFiscalQuarter()).isNull();
        assertThat(found.getFiscalYear()).isEqualTo(2024);
        // scale 4 정밀 보존
        assertThat(found.getEps()).isEqualByComparingTo(new BigDecimal("6.1100"));
        // precision 12 고부채 값 보존(기존 precision 5였다면 오버플로)
        assertThat(found.getDebtRatio()).isEqualByComparingTo(new BigDecimal("209.05"));
    }

    @Test
    @DisplayName("KR 재무행은 periodType·currency가 null로 유지된다(기존 동작 불변)")
    void krFinancialStatement_periodTypeRemainsNull() {
        Stock stock = stockRepository.save(new Stock("005930", "삼성전자", "KOSPI", "반도체"));
        FinancialStatement fs = new FinancialStatement(stock, 2024, 1, LocalDate.of(2024, 3, 31));
        Long id = financialStatementRepository.save(fs).getId();

        entityManager.flush();
        entityManager.clear();

        FinancialStatement found = financialStatementRepository.findById(id).orElseThrow();
        assertThat(found.getPeriodType()).isNull();
        assertThat(found.getCurrency()).isNull();
        assertThat(found.getFiscalQuarter()).isEqualTo(1);
    }
}
