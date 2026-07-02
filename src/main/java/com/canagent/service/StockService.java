package com.canagent.service;

import com.canagent.domain.stock.Stock;
import com.canagent.repository.StockRepository;
import com.canagent.service.dto.DartCompanyDTO;
import com.canagent.service.dto.KrxCorpDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockRepository stockRepository;
    private final KrxApiClient krxApiClient;
    private final DartApiClient dartApiClient;

    public StockService(StockRepository stockRepository, KrxApiClient krxApiClient, DartApiClient dartApiClient) {
        this.stockRepository = stockRepository;
        this.krxApiClient = krxApiClient;
        this.dartApiClient = dartApiClient;
    }

    public List<Stock> getAllActiveStocks() {
        return stockRepository.findByActiveTrue();
    }

    public List<Stock> getAllStocks() {
        return stockRepository.findAll();
    }

    public Optional<Stock> getStockByCode(String code) {
        return stockRepository.findByCode(code);
    }

    public Optional<Stock> getStockById(Long id) {
        return stockRepository.findById(id);
    }

    public List<Stock> searchStocks(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return stockRepository.findByActiveTrue();
        }
        return stockRepository.findByCodeContainingOrNameContainingAndActiveTrue(keyword, keyword);
    }

    public List<Stock> searchAllStocks(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return stockRepository.findAll();
        }
        return stockRepository.findByCodeContainingOrNameContaining(keyword, keyword);
    }

    public List<Stock> getStocksByMarket(String market) {
        return stockRepository.findByMarketAndActiveTrue(market);
    }

    @Transactional
    public Stock registerStock(String code, String name, String market, String sector) {
        if (stockRepository.existsByCode(code)) {
            throw new IllegalArgumentException("이미 등록된 종목입니다: " + code);
        }
        Stock stock = new Stock(code, name, market, sector);
        return stockRepository.save(stock);
    }

    @Transactional
    public Stock registerStockFromKrx(KrxCorpDTO krxCorp) {
        String code = krxCorp.getStockCode();
        Optional<Stock> existing = stockRepository.findByCode(code);

        if (existing.isPresent()) {
            Stock stock = existing.get();
            if (!stock.isActive()) {
                stock.updateInfo(krxCorp.getItemName(), krxCorp.getMarketCategory());
                activateStock(stock.getId());
            }
            return stock;
        }

        Stock stock = new Stock(
                code,
                krxCorp.getItemName(),
                krxCorp.getMarketCategory(),
                krxCorp.getMarketCategory()
        );
        return stockRepository.save(stock);
    }

    @Transactional
    public int syncStockListFromKrx() {
        LocalDate today = LocalDate.now();
        LocalDate baseDate = today;
        int attempts = 0;
        while (attempts < 7) {
            List<KrxCorpDTO> testBatch = krxApiClient.getStockList(
                    baseDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")), 1, 1);
            if (!testBatch.isEmpty()) {
                break;
            }
            baseDate = baseDate.minusDays(1);
            attempts++;
        }

        String baseDateStr = baseDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        log.info("KRX 종목 리스트 동기화 시작: 기준일 {}", baseDateStr);
        List<KrxCorpDTO> krxStocks = krxApiClient.getAllStockList(baseDateStr);

        int registered = 0;
        int updated = 0;

        for (KrxCorpDTO krxStock : krxStocks) {
            try {
                String code = krxStock.getStockCode();
                if (code != null && code.startsWith("A") && code.length() == 7) {
                    code = code.substring(1);
                }
                Optional<Stock> existing = stockRepository.findByCode(code);

                if (existing.isPresent()) {
                    Stock stock = existing.get();
                    stock.updateInfo(krxStock.getItemName(), krxStock.getMarketCategory());
                    stockRepository.save(stock);
                    updated++;
                } else {
                    Stock stock = new Stock(
                            code,
                            krxStock.getItemName(),
                            krxStock.getMarketCategory(),
                            krxStock.getMarketCategory()
                    );
                    stockRepository.save(stock);
                    registered++;
                }
            } catch (Exception e) {
                log.error("종목 동기화 실패: {} - {}", krxStock.getStockCode(), e.getMessage());
            }
        }

        log.info("KRX 종목 리스트 동기화 완료: 신규 {}건, 업데이트 {}건", registered, updated);
        return registered;
    }

    @Transactional
    public Stock activateStock(Long stockId) {
        Stock stock = stockRepository.findById(stockId)
                .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다: " + stockId));
        stock.activate();
        return stockRepository.save(stock);
    }

    @Transactional
    public Stock deactivateStock(Long stockId) {
        Stock stock = stockRepository.findById(stockId)
                .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다: " + stockId));
        stock.deactivate();
        return stockRepository.save(stock);
    }

    @Transactional
    public Stock updateStock(Long stockId, String name, String sector) {
        Stock stock = stockRepository.findById(stockId)
                .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다: " + stockId));
        stock.updateInfo(name, sector);
        return stockRepository.save(stock);
    }

    public long getActiveStockCount() {
        return stockRepository.countByActiveTrue();
    }

    public long getStockCountByMarket(String market) {
        return stockRepository.countByMarketAndActiveTrue(market);
    }

    @Transactional
    public int registerPresetStocks() {
        String[][] presets = {
            {"005930", "삼성전자", "KOSPI"},
            {"000660", "SK하이닉스", "KOSPI"},
            {"035720", "카카오", "KOSPI"},
            {"035420", "네이버", "KOSPI"},
            {"005380", "현대차", "KOSPI"},
            {"000270", "기아", "KOSPI"},
            {"051910", "LG화학", "KOSPI"},
            {"028260", "삼성물산", "KOSPI"},
            {"006400", "삼성SDI", "KOSPI"},
            {"012330", "현대모비스", "KOSPI"},
            {"207940", "삼성바이오로직스", "KOSPI"},
            {"068270", "셀트리온", "KOSPI"},
            {"326030", "주성엔지니어링", "KOSDAQ"},
            {"091640", "제이앤티지", "KOSDAQ"},
            {"247540", "에코프로비엠", "KOSDAQ"},
            {"122870", "유한양행", "KOSPI"},
            {"015760", "한국전력", "KOSPI"},
            {"008930", "한미 반도체", "KOSPI"},
            {"105840", "두산퓨얼셀", "KOSPI"},
            {"034730", "SK", "KOSPI"},
        };

        int registered = 0;
        for (String[] p : presets) {
            try {
                if (!stockRepository.existsByCode(p[0])) {
                    Stock stock = new Stock(p[0], p[1], p[2], p[2]);
                    stockRepository.save(stock);
                    registered++;
                }
            } catch (Exception e) {
                log.warn("프리셋 종목 등록 실패: {} - {}", p[0], e.getMessage());
            }
        }
        log.info("인기 종목 프리셋 등록 완료: {}건", registered);
        return registered;
    }

    @Transactional
    public int syncSectorsFromDart() {
        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        int updated = 0;

        for (Stock stock : activeStocks) {
            try {
                DartCompanyDTO company = dartApiClient.getCompanyInfo(stock.getCode());
                if (company != null && company.getIndustryName() != null
                        && !company.getIndustryName().isBlank()) {
                    String newSector = company.getIndustryName();
                    if (!newSector.equals(stock.getSector())) {
                        stock.updateInfo(stock.getName(), newSector);
                        stockRepository.save(stock);
                        updated++;
                    }
                }
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("업종 동기화 실패: {} ({}) - {}", stock.getName(), stock.getCode(), e.getMessage());
            }
        }

        log.info("DART 업종 동기화 완료: {}건 업데이트 (전체 {}개 중)", updated, activeStocks.size());
        return updated;
    }
}
