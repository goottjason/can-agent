package com.canagent.service.edgar;

import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.port.FinancialsPort;
import com.canagent.repository.FinancialStatementRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.edgar.EdgarCompanyFactsParser.ParsedFinancials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * SEC EDGAR 기반 {@link FinancialsPort} 구현 — <b>유일한 FinancialsPort 빈</b>.
 *
 * <p>DashboardController·DataSyncScheduler의 FinancialsPort 주입은 이 어댑터를 사용한다(시그니처 불변).
 * (구)DART 동기화 클래스는 P9에서 삭제됨.
 *
 * <p>companyfacts JSON → {@link EdgarCompanyFactsParser} → FinancialStatement upsert.
 */
@Service
public class EdgarFinancialsAdapter implements FinancialsPort {

    private static final Logger log = LoggerFactory.getLogger(EdgarFinancialsAdapter.class);

    private final EdgarClient edgarClient;
    private final EdgarCompanyFactsParser parser;
    private final StockRepository stockRepository;
    private final FinancialStatementRepository financialStatementRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EdgarFinancialsAdapter(EdgarClient edgarClient,
                                  EdgarCompanyFactsParser parser,
                                  StockRepository stockRepository,
                                  FinancialStatementRepository financialStatementRepository) {
        this.edgarClient = edgarClient;
        this.parser = parser;
        this.stockRepository = stockRepository;
        this.financialStatementRepository = financialStatementRepository;
    }

    /**
     * active + cik 보유 종목의 companyfacts를 조회·파싱·upsert한다.
     *
     * <p>EDGAR companyfacts는 <b>전체 히스토리를 1회 호출로</b> 반환하므로 {@code quarter}는 사용하지 않는다.
     * {@code year}는 "이 연도 이후만 적재"하는 하한 힌트로 해석한다(파싱 불가/누락 시 전체 히스토리 upsert).
     * 반환값은 upsert된 FinancialStatement 건수.
     */
    @Override
    public int syncAllActiveStocks(String year, String quarter) {
        Integer minYear = parseYear(year);
        List<Stock> stocks = stockRepository.findByActiveTrue();

        int total = 0;
        int skippedNoCik = 0;
        for (Stock stock : stocks) {
            String cik = stock.getCik();
            if (cik == null || cik.isBlank()) {
                skippedNoCik++;
                log.debug("EDGAR skip(cik 없음): {} ({})", stock.getName(), stock.getCode());
                continue;
            }
            try {
                total += syncStock(stock, minYear);
            } catch (Exception e) {
                log.warn("EDGAR 재무 동기화 실패: {} (cik {}) - {}", stock.getName(), cik, e.getMessage());
            }
        }

        log.info("EDGAR 전체 재무 동기화 완료: {}건 upsert (cik 없음 {}건 skip, minYear={})",
                total, skippedNoCik, minYear);
        return total;
    }

    /**
     * 로컬 EDGAR companyfacts JSON 파일을 적재한다(테스트/백필용). JSON의 {@code cik}로 종목을 매칭한다.
     */
    @Override
    @Transactional
    public int importFromJsonFile(String filePath) {
        try {
            JsonNode facts = objectMapper.readTree(new File(filePath));
            String cik = facts.path("cik").asText(null);
            Stock stock = findStockByCik(cik);
            if (stock == null) {
                log.warn("EDGAR JSON 임포트: CIK {} 매칭 종목 없음", cik);
                return 0;
            }
            int n = upsertAll(stock, parser.parse(facts), null);
            log.info("EDGAR JSON 임포트 완료: {}건 저장 ({})", n, stock.getName());
            return n;
        } catch (Exception e) {
            log.error("EDGAR JSON 임포트 실패: {}", e.getMessage());
            return 0;
        }
    }

    @Transactional
    protected int syncStock(Stock stock, Integer minYear) {
        JsonNode facts = edgarClient.getCompanyFacts(stock.getCik());
        if (facts == null) {
            return 0;
        }
        return upsertAll(stock, parser.parse(facts), minYear);
    }

    private int upsertAll(Stock stock, List<ParsedFinancials> parsed, Integer minYear) {
        int n = 0;
        for (ParsedFinancials pf : parsed) {
            if (minYear != null && pf.getFiscalYear() < minYear) continue;
            upsert(stock, pf);
            n++;
        }
        return n;
    }

    private void upsert(Stock stock, ParsedFinancials pf) {
        Optional<FinancialStatement> existing = pf.getFiscalQuarter() == null
                ? financialStatementRepository
                        .findByStockIdAndFiscalYearAndFiscalQuarterIsNull(stock.getId(), pf.getFiscalYear())
                : financialStatementRepository
                        .findByStockIdAndFiscalYearAndFiscalQuarter(stock.getId(), pf.getFiscalYear(), pf.getFiscalQuarter());

        FinancialStatement statement = existing.orElseGet(() ->
                new FinancialStatement(stock, pf.getFiscalYear(), pf.getFiscalQuarter(), pf.getReportDate()));

        statement.setPeriodType(pf.getPeriodType());
        statement.setCurrency(pf.getCurrency());
        statement.updateFinancials(pf.getRevenue(), pf.getOperatingIncome(), pf.getNetIncome(),
                pf.getEps(), pf.getRoe(), pf.getDebtRatio());

        financialStatementRepository.save(statement);
    }

    private Stock findStockByCik(String cik) {
        if (cik == null || cik.isBlank()) return null;
        // company_tickers.json은 cik를 정수(패딩 없음)로 제공 → 정규화해 매칭.
        Optional<Stock> byRaw = stockRepository.findByCik(cik.trim());
        if (byRaw.isPresent()) return byRaw.get();
        String normalized = String.valueOf(Long.parseLong(cik.trim().replaceAll("[^0-9]", "")));
        return stockRepository.findByCik(normalized).orElse(null);
    }

    private Integer parseYear(String year) {
        if (year == null || year.isBlank()) return null;
        try {
            return Integer.parseInt(year.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
