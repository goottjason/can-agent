package com.canagent.service;

import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.repository.FinancialStatementRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.dto.DartFinancialDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class DartDataSyncService {

    private static final Logger log = LoggerFactory.getLogger(DartDataSyncService.class);

    private final DartApiClient dartApiClient;
    private final StockRepository stockRepository;
    private final FinancialStatementRepository financialStatementRepository;

    public DartDataSyncService(DartApiClient dartApiClient,
                               StockRepository stockRepository,
                               FinancialStatementRepository financialStatementRepository) {
        this.dartApiClient = dartApiClient;
        this.stockRepository = stockRepository;
        this.financialStatementRepository = financialStatementRepository;
    }

    @Transactional
    public int syncFinancialStatements(String stockCode, String year, String quarter) {
        Optional<Stock> stockOpt = stockRepository.findByCode(stockCode);
        if (stockOpt.isEmpty()) {
            log.warn("종목 미등록: {}", stockCode);
            return 0;
        }

        Stock stock = stockOpt.get();
        List<DartFinancialDTO> financials = dartApiClient.getFinancialStatements(stockCode, year, quarter);

        if (financials.isEmpty()) {
            log.warn("재무제표 데이터 없음: {} - {}년 {}분기", stockCode, year, quarter);
            return 0;
        }

        Integer fiscalYear = Integer.parseInt(year);
        Integer fiscalQuarter = Integer.parseInt(quarter);

        Optional<FinancialStatement> existing = financialStatementRepository
                .findByStockIdAndFiscalYearAndFiscalQuarter(stock.getId(), fiscalYear, fiscalQuarter);

        FinancialStatement statement;
        if (existing.isPresent()) {
            statement = existing.get();
        } else {
            statement = new FinancialStatement(stock, fiscalYear, fiscalQuarter, LocalDate.now());
        }

        BigDecimal revenue = extractValue(financials, "매출액");
        BigDecimal operatingIncome = extractValue(financials, "영업이익");
        BigDecimal netIncome = extractValue(financials, "당기순이익");
        BigDecimal eps = extractValue(financials, "주당순이익");
        BigDecimal roe = extractValue(financials, "자기자본이익률");
        BigDecimal debtRatio = extractValue(financials, "부채비율");

        statement.updateFinancials(revenue, operatingIncome, netIncome, eps, roe, debtRatio);
        financialStatementRepository.save(statement);

        log.info("재무제표 동기화 완료: {} - {}년 {}분기", stockCode, year, quarter);
        return 1;
    }

    @Transactional
    public int syncAllActiveStocks(String year, String quarter) {
        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        int syncCount = 0;

        for (Stock stock : activeStocks) {
            try {
                int result = syncFinancialStatements(stock.getCode(), year, quarter);
                syncCount += result;
            } catch (Exception e) {
                log.error("재무제표 동기화 실패: {} ({}) - {}", stock.getName(), stock.getCode(), e.getMessage());
            }
        }

        log.info("전체 재무제표 동기화 완료: {}건 저장", syncCount);
        return syncCount;
    }

    private BigDecimal extractValue(List<DartFinancialDTO> financials, String accountName) {
        return financials.stream()
                .filter(f -> accountName.equals(f.getAccountName()))
                .map(f -> parseBigDecimal(f.getCurrentAmount()))
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", "").replace("-", "0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
