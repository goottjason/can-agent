package com.canagent.service;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
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
public class KrxDataSyncService {

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

    @Transactional
    public int syncAllActiveStocks(LocalDate startDate, LocalDate endDate) {
        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        int totalSaved = 0;

        for (Stock stock : activeStocks) {
            try {
                int saved = syncDailyPrices(stock.getCode(), startDate, endDate);
                totalSaved += saved;
            } catch (Exception e) {
                log.error("종목 동기화 실패: {} ({}) - {}", stock.getName(), stock.getCode(), e.getMessage());
            }
        }

        log.info("전체 주가 동기화 완료: {}건 저장", totalSaved);
        return totalSaved;
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
