package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.port.BrokerPort;
import com.canagent.port.dto.BrokerBalance;
import com.canagent.port.dto.OrderResult;
import com.canagent.port.dto.OrderSpec;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import com.canagent.service.analysis.CanSlimAnalysisService;
import com.canagent.service.analysis.CupAndHandleAnalyzer;
import com.canagent.service.dto.CanSlimResult;
import com.canagent.service.dto.CupAndHandleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
public class TradingStrategyService {

    private static final Logger log = LoggerFactory.getLogger(TradingStrategyService.class);

    private final CanSlimAnalysisService canSlimAnalysisService;
    private final CupAndHandleAnalyzer cupAndHandleAnalyzer;
    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;
    private final BrokerPort brokerPort;
    private final ApiConfig apiConfig;

    @Value("${trading.max-positions:10}")
    private int maxPositions;

    @Value("${trading.position-rate:10}")
    private int positionRate;

    @Value("${trading.stop-loss-rate:7}")
    private BigDecimal stopLossRate;

    @Value("${trading.take-profit-rate:20}")
    private BigDecimal takeProfitRate;

    // 매수 최소 총점. 유효값은 application.yml의 trading.min-score(=120)에서 오며,
    // 워커 경로와 동일한 기본값(120)으로 통일한다. (R5 — 60/120 불일치 제거)
    @Value("${trading.min-score:120}")
    private int minScore;

    @Value("${trading.real-trading:false}")
    private boolean realTrading;

    public TradingStrategyService(
            CanSlimAnalysisService canSlimAnalysisService,
            CupAndHandleAnalyzer cupAndHandleAnalyzer,
            PortfolioRepository portfolioRepository,
            TradeRepository tradeRepository,
            BrokerPort brokerPort,
            ApiConfig apiConfig) {
        this.canSlimAnalysisService = canSlimAnalysisService;
        this.cupAndHandleAnalyzer = cupAndHandleAnalyzer;
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
        this.brokerPort = brokerPort;
        this.apiConfig = apiConfig;
    }

    public TradingDecision evaluateBuy(Stock stock, BigDecimal currentPrice) {
        log.info("매수 평가: {} ({})", stock.getName(), stock.getCode());

        CanSlimResult canSlimResult = canSlimAnalysisService.analyze(stock);
        CupAndHandleResult cupResult = cupAndHandleAnalyzer.analyze(stock);

        boolean canSlimBuy = canSlimResult.isBuySignal();
        boolean cupBuy = cupResult.isBuySignal() || cupResult.patternType() == CupAndHandleResult.PatternType.HANDLE_COMPLETE;

        Optional<Portfolio> existingPosition = portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (existingPosition.isPresent()) {
            return TradingDecision.hold("이미 보유 중인 종목");
        }

        long activePositions = portfolioRepository.findByActiveTrue().size();
        if (activePositions >= maxPositions) {
            return TradingDecision.hold("최대 보유 종목 수 도달");
        }

        BrokerBalance balance = brokerPort.getBalance();
        if (!balance.success()) {
            log.warn("잔고 조회 실패: {}", balance.message());
            return TradingDecision.hold("잔고 조회 실패");
        }

        BigDecimal availableCash = balance.availableCash();

        BigDecimal totalScore = canSlimResult.totalScore().add(cupResult.score());
        if (totalScore.compareTo(new BigDecimal(String.valueOf(minScore))) < 0) {
            return TradingDecision.hold("점수 미충족: " + totalScore + " < " + minScore);
        }

        // P6(§3): notional 사이징 — 정수 FLOOR 제거. 주문 금액 = 예수금 × positionRate/100.
        // 소수 수량은 orderAmount/price로 도메인 기록용만 계산(소수 보존, 4자리).
        BigDecimal maxInvestAmount = availableCash.multiply(new BigDecimal(positionRate))
                .divide(new BigDecimal("100"), 2, RoundingMode.FLOOR);

        if (maxInvestAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return TradingDecision.hold("주문 금액 0 (예수금: " + availableCash + ")");
        }

        BigDecimal quantity = maxInvestAmount.divide(currentPrice, 4, RoundingMode.FLOOR);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return TradingDecision.hold("매수 수량 0 (예수금: " + availableCash + ")");
        }

        if (canSlimBuy && cupBuy) {
            String reason = String.format("강력 매수 - CANSLIM: %s, 컵앤핸들: %s, 예수금: %s원, 배분: %s원",
                    canSlimResult.totalScore(), cupResult.score(), availableCash, maxInvestAmount);
            return TradingDecision.buy(quantity, reason);
        }

        if (canSlimBuy) {
            String reason = String.format("CANSLIM 매수 신호 - 점수: %s, 예수금: %s원, 배분: %s원",
                    canSlimResult.totalScore(), availableCash, maxInvestAmount);
            return TradingDecision.buy(quantity, reason);
        }

        if (cupBuy) {
            String reason = String.format("컵앤핸들 매수 신호 - 패턴: %s, 점수: %s, 예수금: %s원, 배분: %s원",
                    cupResult.patternType(), cupResult.score(), availableCash, maxInvestAmount);
            return TradingDecision.buy(quantity, reason);
        }

        return TradingDecision.hold("매수 조건 미충족");
    }

    public TradingDecision evaluateSell(Stock stock, BigDecimal currentPrice) {
        log.info("매도 평가: {} ({})", stock.getName(), stock.getCode());

        Optional<Portfolio> portfolioOpt = portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (portfolioOpt.isEmpty()) {
            return TradingDecision.hold("보유 종목 아님");
        }

        Portfolio portfolio = portfolioOpt.get();
        BigDecimal profitRate = currentPrice.subtract(portfolio.getAverageBuyPrice())
                .divide(portfolio.getAverageBuyPrice(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        if (profitRate.compareTo(stopLossRate.negate()) <= 0) {
            return TradingDecision.sell(
                    portfolio.getQuantity(),
                    String.format("손절 (-%.1f%%)", profitRate.negate())
            );
        }

        if (profitRate.compareTo(takeProfitRate) >= 0) {
            return TradingDecision.sell(
                    portfolio.getQuantity(),
                    String.format("익절 (+%.1f%%)", profitRate)
            );
        }

        CanSlimResult canSlimResult = canSlimAnalysisService.analyze(stock);
        if (canSlimResult.totalScore().compareTo(new BigDecimal("40")) < 0) {
            return TradingDecision.sell(
                    portfolio.getQuantity(),
                    String.format("CANSLIM 점수 하락: %s", canSlimResult.totalScore())
            );
        }

        return TradingDecision.hold("보유 유지");
    }

    @Transactional
    public Trade executeBuy(Stock stock, BigDecimal quantity, BigDecimal price, String reason) {
        log.info("매수 실행: {} {}주 @ {}원 - {}", stock.getName(), quantity, price, reason);

        if (realTrading) {
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("매수 수량 0 이하: {} (가격: {})", quantity, price);
                return null;
            }
            // P6(§3): notional 매수 — 주문 금액 = 수량 × 가격. 소수 시장가 자동 라우팅(어댑터).
            BigDecimal orderAmount = quantity.multiply(price);
            OrderResult response = brokerPort.placeBuy(
                    stock.getCode(), OrderSpec.notional(orderAmount));
            if (!response.success()) {
                log.error("매수 주문 실패: {}", response.message());
                throw new RuntimeException("매수 주문 실패: " + response.message());
            }
        } else {
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("매수 수량 0 이하 (시뮬레이션): {} (가격: {})", quantity, price);
                return null;
            }
        }

        Trade trade = new Trade(stock, TradeType.BUY, quantity, price, reason);

        Optional<Portfolio> existing = portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (existing.isPresent()) {
            Portfolio portfolio = existing.get();
            portfolio.addQuantity(quantity, price);
            portfolioRepository.save(portfolio);
        } else {
            Portfolio portfolio = new Portfolio(stock, quantity, price);
            portfolioRepository.save(portfolio);
        }

        return tradeRepository.save(trade);
    }

    @Transactional
    public Trade executeSell(Stock stock, BigDecimal quantity, BigDecimal price, String reason) {
        log.info("매도 실행: {} {}주 @ {}원 - {}", stock.getName(), quantity, price, reason);

        if (realTrading) {
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("매도 수량 0 이하: {} (가격: {})", quantity, price);
                return null;
            }
            // P6(§3): 소수 수량 매도(전량/부분 청산). 지정가(Limit) — 가격 무손실 BigDecimal.
            // KIS는 정수 절삭·KRW 정수호가로 내부 변환, 토스는 소수 수량 그대로.
            OrderResult response = brokerPort.placeSell(
                    stock.getCode(), OrderSpec.limit(quantity, price));
            if (!response.success()) {
                log.error("매도 주문 실패: {}", response.message());
                throw new RuntimeException("매도 주문 실패: " + response.message());
            }
        } else {
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("매도 수량 0 이하 (시뮬레이션): {} (가격: {})", quantity, price);
                return null;
            }
        }

        Trade trade = new Trade(stock, TradeType.SELL, quantity, price, reason);

        Optional<Portfolio> portfolioOpt = portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (portfolioOpt.isPresent()) {
            Portfolio portfolio = portfolioOpt.get();
            BigDecimal profitRate = price.subtract(portfolio.getAverageBuyPrice())
                    .divide(portfolio.getAverageBuyPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            trade.setProfitRate(profitRate);

            portfolio.reduceQuantity(quantity);
            portfolioRepository.save(portfolio);
        }

        return tradeRepository.save(trade);
    }

    public record TradingDecision(
            TradeType action,
            BigDecimal quantity,
            String reason
    ) {
        public static TradingDecision buy(BigDecimal quantity, String reason) {
            return new TradingDecision(TradeType.BUY, quantity, reason);
        }

        public static TradingDecision sell(BigDecimal quantity, String reason) {
            return new TradingDecision(TradeType.SELL, quantity, reason);
        }

        public static TradingDecision hold(String reason) {
            return new TradingDecision(null, BigDecimal.ZERO, reason);
        }

        public boolean shouldBuy() {
            return action == TradeType.BUY && quantity.compareTo(BigDecimal.ZERO) > 0;
        }

        public boolean shouldSell() {
            return action == TradeType.SELL && quantity.compareTo(BigDecimal.ZERO) > 0;
        }
    }
}
