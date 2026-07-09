package com.canagent.service;

import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.repository.FinancialStatementRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.dto.DartFinancialDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DART(한국) 재무 동기화 서비스.
 *
 * <p>P4(미국 대전환): {@code implements FinancialsPort}를 제거해 포트 빈을 EdgarFinancialsAdapter로 단일화했다.
 * 클래스와 메서드는 vestigial로 유지(P9에서 삭제 예정). syncAllActiveStocks/importFromJsonFile 시그니처는
 * 과거 포트 계약과 동일해 호출 흔적이 남아도 동작은 그대로다.
 */
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
    public int importFromJsonFile(String filePath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String, Object>> data = mapper.readValue(
                    new File(filePath),
                    new TypeReference<Map<String, Map<String, Object>>>() {});

            int imported = 0;
            for (Map.Entry<String, Map<String, Object>> entry : data.entrySet()) {
                Map<String, Object> record = entry.getValue();
                String stockCode = (String) record.get("stock_code");
                String year = (String) record.get("year");
                String quarter = (String) record.get("quarter");
                List<Map<String, String>> items = (List<Map<String, String>>) record.get("items");

                if (stockCode == null || items == null) continue;

                Optional<Stock> stockOpt = stockRepository.findByCode(stockCode);
                if (stockOpt.isEmpty()) continue;

                Stock stock = stockOpt.get();
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

                BigDecimal revenue = extractValueFromMap(items, "매출액");
                BigDecimal operatingIncome = extractValueFromMap(items, "영업이익");
                BigDecimal netIncome = extractValueFromMap(items, "당기순이익");
                if (netIncome.compareTo(BigDecimal.ZERO) == 0) {
                    netIncome = extractValueFromMap(items, "당기순이익(손실)");
                }
                BigDecimal eps = extractValueFromMap(items, "주당순이익");
                BigDecimal roe = extractValueFromMap(items, "자기자본이익률");
                BigDecimal debtRatio = extractValueFromMap(items, "부채비율");

                statement.updateFinancials(revenue, operatingIncome, netIncome, eps, roe, debtRatio);
                financialStatementRepository.save(statement);
                imported++;
            }

            log.info("DART JSON 임포트 완료: {}건 저장", imported);
            return imported;
        } catch (Exception e) {
            log.error("DART JSON 임포트 실패: {}", e.getMessage());
            return 0;
        }
    }

    private BigDecimal extractValueFromMap(List<Map<String, String>> items, String accountName) {
        return items.stream()
                .filter(f -> accountName.equals(f.get("account_nm")))
                .map(f -> parseBigDecimal(f.get("thstrm_amount")))
                .findFirst()
                .orElse(BigDecimal.ZERO);
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

    public int syncAllActiveStocks(String year, String quarter) {
        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        int syncCount = 0;

        for (Stock stock : activeStocks) {
            try {
                int result = syncFinancialStatements(stock.getCode(), year, quarter);
                syncCount += result;
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("재무제표 동기화 실패: {} ({}) - {}", stock.getName(), stock.getCode(), e.getMessage());
            }
        }

        log.info("전체 재무제표 동기화 완료: {}건 저장 ({}년 {}분기)", syncCount, year, quarter);
        return syncCount;
    }

    public int bulkSyncAllActiveStocks(int quarters) {
        log.info("벌크 재무제표 동기화 시작: 과거 {}분기", quarters);

        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        if (activeStocks.isEmpty()) {
            log.warn("활성 종목 없음");
            return 0;
        }

        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        int currentQuarter = (today.getMonthValue() - 1) / 3 + 1;

        int totalCount = 0;

        for (int i = 0; i < quarters; i++) {
            int q = currentQuarter - i;
            int y = currentYear;
            while (q <= 0) {
                q += 4;
                y--;
            }

            String year = String.valueOf(y);
            String quarter = String.valueOf(q);

            log.info("재무제표 동기화: {}년 {}분기", year, quarter);

            for (Stock stock : activeStocks) {
                try {
                    int result = syncFinancialStatements(stock.getCode(), year, quarter);
                    totalCount += result;
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("벌크 재무 동기화 중단");
                    return totalCount;
                } catch (Exception e) {
                    log.error("재무제표 동기화 실패: {} ({}) - {}년 {}분기 - {}",
                            stock.getName(), stock.getCode(), year, quarter, e.getMessage());
                }
            }

            log.info("{}년 {}분기 동기화 완료", year, quarter);
        }

        log.info("벌크 재무제표 동기화 완료: {}건 저장", totalCount);
        return totalCount;
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
