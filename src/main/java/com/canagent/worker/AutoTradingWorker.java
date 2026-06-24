package com.canagent.worker;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.domain.trading.Trade;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.TradingStrategyService;
import com.canagent.service.TradingStrategyService.TradingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "trading.scheduler.enabled", havingValue = "true")
public class AutoTradingWorker {

    private static final Logger log = LoggerFactory.getLogger(AutoTradingWorker.class);

    private final StockRepository stockRepository;
    private final StockPriceRepository stockPriceRepository;
    private final TradingStrategyService tradingStrategyService;

    public AutoTradingWorker(
            StockRepository stockRepository,
            StockPriceRepository stockPriceRepository,
            TradingStrategyService tradingStrategyService) {
        this.stockRepository = stockRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.tradingStrategyService = tradingStrategyService;
    }

    @Scheduled(cron = "${trading.scheduler.cron:0 0 9 * * MON-FRI}")
    public void executeTrading() {
        log.info("===== 자동매매 워커 시작 =====");

        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        log.info("대상 종목 수: {}", activeStocks.size());

        for (Stock stock : activeStocks) {
            try {
                processStock(stock);
            } catch (Exception e) {
                log.error("종목 처리 실패: {} ({}) - {}",
                        stock.getName(), stock.getCode(), e.getMessage());
            }
        }

        log.info("===== 자동매매 워커 종료 =====");
    }

    private void processStock(Stock stock) {
        Optional<StockPrice> latestPriceOpt = stockPriceRepository
                .findTopByStockIdOrderByDateDesc(stock.getId());

        if (latestPriceOpt.isEmpty()) {
            log.debug("가격 데이터 없음: {} ({})", stock.getName(), stock.getCode());
            return;
        }

        StockPrice latestPrice = latestPriceOpt.get();
        BigDecimal currentPrice = latestPrice.getClose();

        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("유효하지 않은 가격: {} ({})", stock.getName(), stock.getCode());
            return;
        }

        TradingDecision sellDecision = tradingStrategyService.evaluateSell(stock, currentPrice);
        if (sellDecision.shouldSell()) {
            Trade trade = tradingStrategyService.executeSell(
                    stock,
                    sellDecision.quantity(),
                    currentPrice,
                    sellDecision.reason()
            );
            log.info("매도 실행: {} {}주 @ {}원 - {}",
                    stock.getName(), sellDecision.quantity(), currentPrice, sellDecision.reason());
            return;
        }

        TradingDecision buyDecision = tradingStrategyService.evaluateBuy(stock, currentPrice);
        if (buyDecision.shouldBuy()) {
            Trade trade = tradingStrategyService.executeBuy(
                    stock,
                    buyDecision.quantity(),
                    currentPrice,
                    buyDecision.reason()
            );
            log.info("매수 실행: {} {}주 @ {}원 - {}",
                    stock.getName(), buyDecision.quantity(), currentPrice, buyDecision.reason());
        }
    }

    public void runManualCheck() {
        log.info("수동 검사 시작");
        executeTrading();
        log.info("수동 검사 완료");
    }
}
