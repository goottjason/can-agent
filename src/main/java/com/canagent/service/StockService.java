package com.canagent.service;

import com.canagent.domain.stock.Stock;
import com.canagent.repository.StockRepository;
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

    public StockService(StockRepository stockRepository, KrxApiClient krxApiClient) {
        this.stockRepository = stockRepository;
        this.krxApiClient = krxApiClient;
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
        String baseDate = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        log.info("KRX 종목 리스트 동기화 시작: 기준일 {}", baseDate);
        List<KrxCorpDTO> krxStocks = krxApiClient.getAllStockList(baseDate);

        int registered = 0;
        int updated = 0;

        for (KrxCorpDTO krxStock : krxStocks) {
            try {
                String code = krxStock.getStockCode();
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
        stock.updateInfo(stock.getName(), stock.getSector());
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
}
