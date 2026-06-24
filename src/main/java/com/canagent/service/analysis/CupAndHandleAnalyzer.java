package com.canagent.service.analysis;

import com.canagent.domain.analysis.CupAndHandlePattern;
import com.canagent.domain.analysis.PatternStatus;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.CupAndHandlePatternRepository;
import com.canagent.repository.StockPriceRepository;
import com.canagent.service.dto.CupAndHandleResult;
import com.canagent.service.dto.CupAndHandleResult.PatternType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class CupAndHandleAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CupAndHandleAnalyzer.class);

    private static final BigDecimal MIN_CUP_DEPTH = new BigDecimal("12");
    private static final BigDecimal MAX_CUP_DEPTH = new BigDecimal("33");
    private static final int MIN_CUP_WEEKS = 7;
    private static final int MAX_CUP_WEEKS = 65;
    private static final BigDecimal MIN_HANDLE_DEPTH = new BigDecimal("5");
    private static final BigDecimal MAX_HANDLE_DEPTH = new BigDecimal("15");
    private static final int MAX_HANDLE_WEEKS = 4;

    private final StockPriceRepository stockPriceRepository;
    private final CupAndHandlePatternRepository patternRepository;

    public CupAndHandleAnalyzer(StockPriceRepository stockPriceRepository,
                                 CupAndHandlePatternRepository patternRepository) {
        this.stockPriceRepository = stockPriceRepository;
        this.patternRepository = patternRepository;
    }

    public CupAndHandleResult analyze(Stock stock) {
        log.info("컵앤핸들 분석 시작: {} ({})", stock.getName(), stock.getCode());

        List<StockPrice> prices = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(
                        stock.getId(),
                        LocalDate.now().minusWeeks(MAX_CUP_WEEKS + MAX_HANDLE_WEEKS + 4),
                        LocalDate.now()
                );

        if (prices.size() < 20) {
            return CupAndHandleResult.noPattern(stock.getCode(), stock.getName());
        }

        CupInfo cupInfo = detectCup(prices);
        if (cupInfo == null) {
            return CupAndHandleResult.noPattern(stock.getCode(), stock.getName());
        }

        HandleInfo handleInfo = detectHandle(prices, cupInfo);
        if (handleInfo == null) {
            return buildCupOnlyResult(stock, cupInfo);
        }

        boolean breakout = detectBreakout(prices, handleInfo);

        return buildFullResult(stock, cupInfo, handleInfo, breakout);
    }

    private CupInfo detectCup(List<StockPrice> prices) {
        int size = prices.size();

        for (int i = 0; i < size - MIN_CUP_WEEKS; i++) {
            StockPrice startPrice = prices.get(i);

            for (int j = i + MIN_CUP_WEEKS; j < Math.min(i + MAX_CUP_WEEKS, size); j++) {
                StockPrice endPrice = prices.get(j);

                BigDecimal cupHigh = findHighPrice(prices, i, j);
                BigDecimal cupLow = findLowPrice(prices, i, j);

                if (cupHigh.compareTo(BigDecimal.ZERO) == 0) continue;

                BigDecimal depth = cupHigh.subtract(cupLow)
                        .divide(cupHigh, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                if (depth.compareTo(MIN_CUP_DEPTH) >= 0 && depth.compareTo(MAX_CUP_DEPTH) <= 0) {
                    if (endPrice.getClose().compareTo(cupHigh.multiply(new BigDecimal("0.95"))) >= 0) {
                        long weeks = ChronoUnit.WEEKS.between(startPrice.getDate(), endPrice.getDate());

                        if (weeks >= MIN_CUP_WEEKS && weeks <= MAX_CUP_WEEKS) {
                            return new CupInfo(
                                    startPrice.getDate(),
                                    endPrice.getDate(),
                                    cupHigh,
                                    cupLow,
                                    depth,
                                    j
                            );
                        }
                    }
                }
            }
        }

        return null;
    }

    private HandleInfo detectHandle(List<StockPrice> prices, CupInfo cupInfo) {
        int cupEndIndex = cupInfo.endIndex;

        for (int i = cupEndIndex; i < Math.min(cupEndIndex + MAX_HANDLE_WEEKS + 1, prices.size()); i++) {
            StockPrice handleStart = prices.get(cupEndIndex);
            StockPrice handleEnd = prices.get(i);

            BigDecimal handleHigh = findHighPrice(prices, cupEndIndex, i);
            BigDecimal handleLow = findLowPrice(prices, cupEndIndex, i);

            if (handleHigh.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal handleDepth = handleHigh.subtract(handleLow)
                    .divide(handleHigh, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            if (handleDepth.compareTo(MIN_HANDLE_DEPTH) >= 0 &&
                    handleDepth.compareTo(MAX_HANDLE_DEPTH) <= 0) {
                long weeks = ChronoUnit.WEEKS.between(handleStart.getDate(), handleEnd.getDate());

                if (weeks <= MAX_HANDLE_WEEKS) {
                    return new HandleInfo(
                            handleStart.getDate(),
                            handleEnd.getDate(),
                            handleHigh,
                            handleLow,
                            handleDepth,
                            i
                    );
                }
            }
        }

        return null;
    }

    private boolean detectBreakout(List<StockPrice> prices, HandleInfo handleInfo) {
        if (prices.isEmpty()) return false;

        StockPrice latestPrice = prices.get(prices.size() - 1);
        return latestPrice.getClose().compareTo(handleInfo.high) > 0;
    }

    private BigDecimal findHighPrice(List<StockPrice> prices, int start, int end) {
        return prices.subList(start, end + 1).stream()
                .map(StockPrice::getHigh)
                .filter(p -> p != null)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal findLowPrice(List<StockPrice> prices, int start, int end) {
        return prices.subList(start, end + 1).stream()
                .map(StockPrice::getLow)
                .filter(p -> p != null)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private CupAndHandleResult buildCupOnlyResult(Stock stock, CupInfo cupInfo) {
        BigDecimal score = calculateCupScore(cupInfo);

        return new CupAndHandleResult(
                stock.getCode(),
                stock.getName(),
                PatternType.CUP_FORMING,
                score,
                cupInfo.startDate,
                cupInfo.endDate,
                cupInfo.depth,
                null, null, null,
                null, null,
                false,
                String.format("컵 패턴 형성 중 (깊이: %.1f%%, 기간: %d주)",
                        cupInfo.depth, ChronoUnit.WEEKS.between(cupInfo.startDate, cupInfo.endDate))
        );
    }

    private CupAndHandleResult buildFullResult(Stock stock, CupInfo cupInfo,
                                                HandleInfo handleInfo, boolean breakout) {
        BigDecimal score = calculateFullScore(cupInfo, handleInfo, breakout);

        PatternType type = breakout ? PatternType.BREAKOUT : PatternType.HANDLE_COMPLETE;
        BigDecimal targetPrice = cupInfo.high.add(cupInfo.high.subtract(cupInfo.low));

        return new CupAndHandleResult(
                stock.getCode(),
                stock.getName(),
                type,
                score,
                cupInfo.startDate,
                cupInfo.endDate,
                cupInfo.depth,
                handleInfo.startDate,
                handleInfo.endDate,
                handleInfo.depth,
                breakout ? handleInfo.high : null,
                targetPrice,
                breakout,
                breakout ?
                        String.format("돌파 발생! 목표가: %s", targetPrice) :
                        String.format("핸들 형성 완료 (깊이: %.1f%%)", handleInfo.depth)
        );
    }

    private BigDecimal calculateCupScore(CupInfo cupInfo) {
        BigDecimal score = new BigDecimal("30");

        if (cupInfo.depth.compareTo(new BigDecimal("20")) >= 0 &&
                cupInfo.depth.compareTo(new BigDecimal("25")) <= 0) {
            score = score.add(new BigDecimal("10"));
        }

        long weeks = ChronoUnit.WEEKS.between(cupInfo.startDate, cupInfo.endDate);
        if (weeks >= 7 && weeks <= 30) {
            score = score.add(new BigDecimal("10"));
        }

        return score;
    }

    private BigDecimal calculateFullScore(CupInfo cupInfo, HandleInfo handleInfo, boolean breakout) {
        BigDecimal score = calculateCupScore(cupInfo);

        if (handleInfo.depth.compareTo(new BigDecimal("10")) >= 0 &&
                handleInfo.depth.compareTo(new BigDecimal("12")) <= 0) {
            score = score.add(new BigDecimal("20"));
        } else {
            score = score.add(new BigDecimal("10"));
        }

        if (breakout) {
            score = score.add(new BigDecimal("20"));
        }

        return score;
    }

    private record CupInfo(
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal high,
            BigDecimal low,
            BigDecimal depth,
            int endIndex
    ) {}

    private record HandleInfo(
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal high,
            BigDecimal low,
            BigDecimal depth,
            int endIndex
    ) {}
}
