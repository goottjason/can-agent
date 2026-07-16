package com.canagent.service;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.trading.TradeType;
import com.canagent.port.BrokerPort;
import com.canagent.port.dto.BrokerBalance;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.StockRepository;
import com.canagent.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;
    private final BrokerPort brokerPort;
    private final StockRepository stockRepository;

    @Value("${trading.max-positions:10}")
    private int maxPositions;

    @Value("${trading.position-size:1000000}")
    private BigDecimal positionSize;

    public PortfolioService(PortfolioRepository portfolioRepository,
                           TradeRepository tradeRepository,
                           BrokerPort brokerPort,
                           StockRepository stockRepository) {
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
        this.brokerPort = brokerPort;
        this.stockRepository = stockRepository;
    }

    public List<Portfolio> getActivePortfolios() {
        return portfolioRepository.findByActiveTrue();
    }

    public BigDecimal getTotalInvestment() {
        return portfolioRepository.sumTotalBuyAmountByActiveTrue();
    }

    public BigDecimal getTotalCurrentValue() {
        return portfolioRepository.sumTotalCurrentValueByActiveTrue();
    }

    public BigDecimal getTotalProfit() {
        return portfolioRepository.sumTotalProfitAmountByActiveTrue();
    }

    public BigDecimal getTotalProfitRate() {
        BigDecimal totalInvestment = getTotalInvestment();
        if (totalInvestment.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return getTotalProfit()
                .divide(totalInvestment, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    public long getActivePortfolioCount() {
        return portfolioRepository.countByActiveTrue();
    }

    public int getAvailableSlots() {
        long activeCount = getActivePortfolioCount();
        return (int) Math.max(0, maxPositions - activeCount);
    }

    public boolean canOpenNewPosition() {
        return getAvailableSlots() > 0;
    }

    public BigDecimal getAvailableInvestmentAmount() {
        BigDecimal totalInvestment = getTotalInvestment();
        BigDecimal maxTotalInvestment = positionSize.multiply(new BigDecimal(maxPositions));
        return maxTotalInvestment.subtract(totalInvestment).max(BigDecimal.ZERO);
    }

    public BigDecimal getPositionWeight(Portfolio portfolio) {
        BigDecimal totalCurrentValue = getTotalCurrentValue();
        if (totalCurrentValue.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal positionValue = portfolio.getCurrentPrice()
                .multiply(portfolio.getQuantity());
        return positionValue
                .divide(totalCurrentValue, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    public Map<String, Object> getPortfolioSummary() {
        BigDecimal totalInvestment = getTotalInvestment();
        BigDecimal totalCurrentValue = getTotalCurrentValue();
        BigDecimal totalProfit = getTotalProfit();
        BigDecimal totalProfitRate = getTotalProfitRate();

        List<Portfolio> activePortfolios = getActivePortfolios();
        long activeCount = getActivePortfolioCount();
        int availableSlots = getAvailableSlots();

        List<Portfolio> lossPortfolios = portfolioRepository.findLossPortfolios(new BigDecimal("-5"));
        List<Portfolio> topProfitPortfolios = portfolioRepository.findTopProfitPortfolios();

        return Map.of(
                "totalInvestment", totalInvestment,
                "totalCurrentValue", totalCurrentValue,
                "totalProfit", totalProfit,
                "totalProfitRate", totalProfitRate,
                "activeCount", activeCount,
                "availableSlots", availableSlots,
                "maxPositions", maxPositions,
                "positionSize", positionSize,
                "lossPortfolios", lossPortfolios,
                "topProfitPortfolios", topProfitPortfolios.stream().limit(5).collect(Collectors.toList())
        );
    }

    public Map<String, Object> getTradeStatistics() {
        long buyCount = tradeRepository.countByTradeType(TradeType.BUY);
        long sellCount = tradeRepository.countByTradeType(TradeType.SELL);

        BigDecimal totalBuyAmount = tradeRepository.sumTotalAmountByTradeType(TradeType.BUY);
        BigDecimal totalSellAmount = tradeRepository.sumTotalAmountByTradeType(TradeType.SELL);

        long winningTrades = tradeRepository.countWinningSellTrades();
        double winRate = sellCount > 0 ? (double) winningTrades / sellCount * 100 : 0;

        long recentTradeCount = tradeRepository.countByTradeDateTimeAfter(LocalDateTime.now().minusDays(7));

        return Map.of(
                "totalBuyCount", buyCount,
                "totalSellCount", sellCount,
                "totalBuyAmount", totalBuyAmount,
                "totalSellAmount", totalSellAmount,
                "winRate", BigDecimal.valueOf(winRate).setScale(1, RoundingMode.HALF_UP),
                "recentTradeCount", recentTradeCount
        );
    }

    public Map<String, Object> getRiskStatus() {
        List<Portfolio> activePortfolios = getActivePortfolios();
        BigDecimal totalCurrentValue = getTotalCurrentValue();
        BigDecimal availableAmount = getAvailableInvestmentAmount();

        boolean isMaxPositions = getAvailableSlots() == 0;
        boolean isLowCash = availableAmount.compareTo(positionSize) < 0;

        List<Portfolio> highRiskPortfolios = activePortfolios.stream()
                .filter(p -> getPositionWeight(p).compareTo(new BigDecimal("20")) > 0)
                .toList();

        List<Portfolio> lossPortfolios = portfolioRepository.findLossPortfolios(new BigDecimal("-5"));

        return Map.of(
                "isMaxPositions", isMaxPositions,
                "isLowCash", isLowCash,
                "highRiskCount", highRiskPortfolios.size(),
                "lossCount", lossPortfolios.size(),
                "availableAmount", availableAmount,
                "totalCurrentValue", totalCurrentValue
        );
    }

    /**
     * 실계좌 동기화 결과 요약. dashboard flash 메시지에 그대로 노출된다.
     *
     * @param reconciled     조정 수행 여부(브로커 조회 실패 시 false — 아무 변경 없음)
     * @param brokerHoldings 브로커 실보유 종목 수
     * @param deactivated    브로커에 없어 청산(active=false) 처리된 DB 포지션 수
     * @param updated        수량/평단이 달라 브로커 값으로 갱신된 포지션 수
     * @param added          브로커엔 있으나 DB active에 없어 신규 생성된 포지션 수
     * @param skipped        브로커 보유이나 DB에 종목이 없어 건너뛴 심볼 목록(경고)
     * @param message        사람이 읽는 결과 요약(실패 사유 포함)
     */
    public record ReconcileSummary(
            boolean reconciled,
            int brokerHoldings,
            int deactivated,
            int updated,
            int added,
            List<String> skipped,
            String message
    ) {}

    /**
     * 실 브로커 보유({@link BrokerPort#getBalance()}의 holdings)를 소스오브트루스로 DB active
     * portfolio를 재조정한다. 관측/phantom 누적으로 desync된 상태를 사용자 트리거로 정합화한다.
     *
     * <p><b>안전장치(최우선):</b> {@code getBalance().success==false}이면 <b>어떤 삭제·갱신도 하지 않고</b>
     * 실패 사유를 요약에 담아 반환한다(조용실패·오삭제 방지).
     */
    @Transactional
    public ReconcileSummary reconcileFromBroker() {
        BrokerBalance balance = brokerPort.getBalance();
        if (!balance.success()) {
            String reason = balance.message() != null ? balance.message() : "사유 불명";
            log.warn("실계좌 동기화 중단 — 브로커 잔고 조회 실패: {} (변경 없음)", reason);
            return new ReconcileSummary(false, 0, 0, 0, 0, List.of(),
                    "브로커 잔고 조회 실패로 동기화 중단(변경 없음): " + reason);
        }

        Map<String, BrokerBalance.Holding> brokerBySymbol = new HashMap<>();
        for (BrokerBalance.Holding h : balance.holdings()) {
            brokerBySymbol.put(h.symbol(), h);
        }

        List<Portfolio> active = portfolioRepository.findByActiveTrue();
        Set<String> matchedSymbols = new HashSet<>();
        int deactivated = 0;
        int updated = 0;

        for (Portfolio p : active) {
            String symbol = p.getStock().getCode();
            BrokerBalance.Holding h = brokerBySymbol.get(symbol);
            if (h == null) {
                // 브로커에 없음 → 청산(하드삭제 금지)
                p.deactivate();
                portfolioRepository.save(p);
                deactivated++;
                log.info("실계좌 동기화: {} 청산(브로커 미보유)", symbol);
            } else {
                matchedSymbols.add(symbol);
                boolean qtyDiff = p.getQuantity().compareTo(h.quantity()) != 0;
                boolean avgDiff = p.getAverageBuyPrice().compareTo(h.avgBuyPrice()) != 0;
                if (qtyDiff || avgDiff) {
                    p.reconcileTo(h.quantity(), h.avgBuyPrice());
                    portfolioRepository.save(p);
                    updated++;
                    log.info("실계좌 동기화: {} 갱신(수량 {}, 평단 {})", symbol, h.quantity(), h.avgBuyPrice());
                }
            }
        }

        int added = 0;
        List<String> skipped = new ArrayList<>();
        for (BrokerBalance.Holding h : balance.holdings()) {
            if (matchedSymbols.contains(h.symbol())) {
                continue;
            }
            Stock stock = stockRepository.findByCode(h.symbol()).orElse(null);
            if (stock == null) {
                skipped.add(h.symbol());
                log.warn("실계좌 동기화: {} 종목이 DB에 없어 건너뜀(신규 포지션 생성 불가)", h.symbol());
                continue;
            }
            portfolioRepository.save(new Portfolio(stock, h.quantity(), h.avgBuyPrice()));
            added++;
            log.info("실계좌 동기화: {} 신규 포지션 생성(수량 {}, 평단 {})", h.symbol(), h.quantity(), h.avgBuyPrice());
        }

        String message = String.format(
                "실계좌 동기화 완료: 브로커 보유 %d, 청산 %d, 갱신 %d, 추가 %d%s",
                balance.holdings().size(), deactivated, updated, added,
                skipped.isEmpty() ? "" : ", 건너뜀 " + skipped.size() + "(" + String.join(",", skipped) + ")");
        log.info(message);
        return new ReconcileSummary(true, balance.holdings().size(),
                deactivated, updated, added, skipped, message);
    }
}
