package com.canagent.service;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.port.MarketDataPort;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.dto.KrxPriceDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class KrxDataSyncService implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(KrxDataSyncService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KrxApiClient krxApiClient;
    private final StockRepository stockRepository;
    private final StockPriceRepository stockPriceRepository;

    public KrxDataSyncService(KrxApiClient krxApiClient,
                              StockRepository stockRepository,
                              StockPriceRepository stockPriceRepository) {
        this.krxApiClient = krxApiClient;
        this.stockRepository = stockRepository;
        this.stockPriceRepository = stockPriceRepository;
    }

    @Transactional
    public int syncDailyPrices(String stockCode, LocalDate startDate, LocalDate endDate) {
        Optional<Stock> stockOpt = stockRepository.findByCode(stockCode);
        if (stockOpt.isEmpty()) {
            log.warn("종목 미등록: {}", stockCode);
            return 0;
        }

        Stock stock = stockOpt.get();
        String startStr = startDate.format(DATE_FORMAT);
        String endStr = endDate.format(DATE_FORMAT);

        List<KrxPriceDTO> prices = krxApiClient.getDailyPrices(stockCode, startStr, endStr);
        int savedCount = 0;

        for (KrxPriceDTO dto : prices) {
            try {
                LocalDate date = LocalDate.parse(dto.getBaseDate(), DATE_FORMAT);

                Optional<StockPrice> existing = stockPriceRepository
                        .findByStockIdAndDateBetweenOrderByDateAsc(stock.getId(), date, date)
                        .stream()
                        .findFirst();

                if (existing.isPresent()) {
                    continue;
                }

                StockPrice stockPrice = new StockPrice(
                        stock,
                        date,
                        parseBigDecimal(dto.getOpeningPrice()),
                        parseBigDecimal(dto.getHighPrice()),
                        parseBigDecimal(dto.getLowPrice()),
                        parseBigDecimal(dto.getClosingPrice()),
                        parseLong(dto.getTradingQuantity())
                );

                stockPrice.setChangeRate(parseBigDecimal(dto.getFluctuationRate()));
                stockPriceRepository.save(stockPrice);
                savedCount++;
            } catch (Exception e) {
                log.error("주가 데이터 저장 실패: {} - {}", stockCode, e.getMessage());
            }
        }

        log.info("주가 동기화 완료: {} - {}건 저장", stockCode, savedCount);
        return savedCount;
    }

    @Override
    @Transactional
    public int syncAllActiveStocks(LocalDate startDate, LocalDate endDate) {
        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        if (activeStocks.isEmpty()) {
            return 0;
        }

        java.util.Set<String> activeCodes = new java.util.HashSet<>();
        java.util.Map<String, Stock> codeToStock = new java.util.HashMap<>();
        for (Stock s : activeStocks) {
            activeCodes.add(s.getCode());
            codeToStock.put(s.getCode(), s);
        }

        int totalSaved = 0;
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            String dateStr = current.format(DATE_FORMAT);
            try {
                List<KrxPriceDTO> allPrices = krxApiClient.getAllDailyPrices(dateStr);
                for (KrxPriceDTO dto : allPrices) {
                    String code = dto.getStockCode();
                    if (!activeCodes.contains(code)) {
                        continue;
                    }
                    Stock stock = codeToStock.get(code);
                    try {
                        LocalDate date = LocalDate.parse(dto.getBaseDate(), DATE_FORMAT);

                        Optional<StockPrice> existing = stockPriceRepository
                                .findByStockIdAndDateBetweenOrderByDateAsc(stock.getId(), date, date)
                                .stream()
                                .findFirst();

                        if (existing.isPresent()) {
                            continue;
                        }

                        StockPrice stockPrice = new StockPrice(
                                stock,
                                date,
                                parseBigDecimal(dto.getOpeningPrice()),
                                parseBigDecimal(dto.getHighPrice()),
                                parseBigDecimal(dto.getLowPrice()),
                                parseBigDecimal(dto.getClosingPrice()),
                                parseLong(dto.getTradingQuantity())
                        );

                        stockPrice.setChangeRate(parseBigDecimal(dto.getFluctuationRate()));
                        stockPriceRepository.save(stockPrice);
                        totalSaved++;
                    } catch (Exception e) {
                        log.error("주가 데이터 저장 실패: {} - {}", code, e.getMessage());
                    }
                }
                log.info("날짜 {} 동기화 완료: {}건 저장 (전체 {}건 중)", dateStr, totalSaved, allPrices.size());
            } catch (Exception e) {
                log.error("날짜 {} 데이터 조회 실패: {}", dateStr, e.getMessage());
            }
            current = current.plusDays(1);
        }

        log.info("전체 주가 동기화 완료: {}건 저장", totalSaved);
        return totalSaved;
    }

    public int bulkSyncAllActiveStocks(int tradingDays) {
        log.info("벌크 주가 동기화 시작: 과거 {} 거래일", tradingDays);

        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        if (activeStocks.isEmpty()) {
            log.warn("활성 종목 없음");
            return 0;
        }

        java.util.Set<String> activeCodes = new java.util.HashSet<>();
        java.util.Map<String, Stock> codeToStock = new java.util.HashMap<>();
        for (Stock s : activeStocks) {
            activeCodes.add(s.getCode());
            codeToStock.put(s.getCode(), s);
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays((long) (tradingDays * 1.6));

        int totalSaved = 0;
        int apiCalls = 0;
        LocalDate current = startDate;

        while (!current.isAfter(endDate)) {
            String dateStr = current.format(DATE_FORMAT);
            try {
                List<KrxPriceDTO> allPrices = krxApiClient.getAllDailyPrices(dateStr);
                apiCalls++;

                int daySaved = 0;
                for (KrxPriceDTO dto : allPrices) {
                    String code = dto.getStockCode();
                    if (!activeCodes.contains(code)) {
                        continue;
                    }
                    Stock stock = codeToStock.get(code);
                    try {
                        LocalDate date = LocalDate.parse(dto.getBaseDate(), DATE_FORMAT);
                        StockPrice saved = savePriceImmediately(stock, date, dto);
                        if (saved != null) {
                            totalSaved++;
                            daySaved++;
                        }
                    } catch (Exception e) {
                        log.error("주가 데이터 저장 실패: {} - {}", code, e.getMessage());
                    }
                }

                if (apiCalls % 10 == 0) {
                    log.info("벌크 동기화 진행 중: {}일 처리, {}건 저장 (이번 날: {}건)", apiCalls, totalSaved, daySaved);
                }
            } catch (Exception e) {
                log.error("날짜 {} 데이터 조회 실패: {}", dateStr, e.getMessage());
            }
            current = current.plusDays(1);
        }

        log.info("벌크 주가 동기화 완료: {}일 처리, {}건 저장 (활성종목 {}개)", apiCalls, totalSaved, activeCodes.size());
        return totalSaved;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public StockPrice savePriceImmediately(Stock stock, LocalDate date, KrxPriceDTO dto) {
        Optional<StockPrice> existing = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(stock.getId(), date, date)
                .stream()
                .findFirst();

        if (existing.isPresent()) {
            return null;
        }

        StockPrice stockPrice = new StockPrice(
                stock,
                date,
                parseBigDecimal(dto.getOpeningPrice()),
                parseBigDecimal(dto.getHighPrice()),
                parseBigDecimal(dto.getLowPrice()),
                parseBigDecimal(dto.getClosingPrice()),
                parseLong(dto.getTradingQuantity())
        );
        stockPrice.setChangeRate(parseBigDecimal(dto.getFluctuationRate()));
        return stockPriceRepository.save(stockPrice);
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
