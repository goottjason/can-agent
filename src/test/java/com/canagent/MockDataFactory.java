package com.canagent;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;

import java.math.BigDecimal;
import java.time.LocalDate;

public class MockDataFactory {

    public static Stock createStock(String code, String name, String market, String sector) {
        return new Stock(code, name, market, sector);
    }

    public static Stock createSamsungStock() {
        return createStock("005930", "삼성전자", "KOSPI", "반도체");
    }

    public static Stock createNaverStock() {
        return createStock("035420", "네이버", "KOSPI", "인터넷");
    }

    public static Stock createKakaoStock() {
        return createStock("035720", "카카오", "KOSPI", "인터넷");
    }

    public static StockPrice createStockPrice(Stock stock, LocalDate date,
                                               BigDecimal open, BigDecimal high,
                                               BigDecimal low, BigDecimal close,
                                               Long volume) {
        return new StockPrice(stock, date, open, high, low, close, volume);
    }

    public static StockPrice createRisingPrice(Stock stock, LocalDate date, BigDecimal close) {
        BigDecimal open = close.subtract(new BigDecimal("1000"));
        BigDecimal high = close.add(new BigDecimal("500"));
        BigDecimal low = open.subtract(new BigDecimal("500"));
        return createStockPrice(stock, date, open, high, low, close, 1000000L);
    }

    public static StockPrice createFallingPrice(Stock stock, LocalDate date, BigDecimal close) {
        BigDecimal open = close.add(new BigDecimal("1000"));
        BigDecimal high = open.add(new BigDecimal("500"));
        BigDecimal low = close.subtract(new BigDecimal("500"));
        return createStockPrice(stock, date, open, high, low, close, 1000000L);
    }

    public static FinancialStatement createFinancialStatement(
            Stock stock, int year, int quarter,
            BigDecimal eps, BigDecimal revenue) {
        var fs = new FinancialStatement(stock, year, quarter, LocalDate.of(year, quarter * 3, 31));
        fs.updateFinancials(
                revenue,
                revenue.multiply(new BigDecimal("0.1")),
                revenue.multiply(new BigDecimal("0.05")),
                eps,
                new BigDecimal("15"),
                new BigDecimal("30")
        );
        return fs;
    }

    public static FinancialStatement createGrowingEarnings(Stock stock) {
        return createFinancialStatement(stock, 2024, 1,
                new BigDecimal("5000"),
                new BigDecimal("50000000000"));
    }

    public static FinancialStatement createPreviousEarnings(Stock stock) {
        return createFinancialStatement(stock, 2023, 1,
                new BigDecimal("4000"),
                new BigDecimal("40000000000"));
    }

    public static Portfolio createPortfolio(Stock stock, int quantity, BigDecimal buyPrice) {
        return new Portfolio(stock, quantity, buyPrice);
    }

    public static Portfolio createProfitablePortfolio() {
        Stock stock = createSamsungStock();
        return createPortfolio(stock, 10, new BigDecimal("70000"));
    }

    public static Portfolio createLosingPortfolio() {
        Stock stock = createNaverStock();
        return createPortfolio(stock, 5, new BigDecimal("300000"));
    }
}
