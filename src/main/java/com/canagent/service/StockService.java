package com.canagent.service;

import com.canagent.domain.stock.Stock;
import com.canagent.repository.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class StockService {

    private final StockRepository stockRepository;

    public StockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
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
}
