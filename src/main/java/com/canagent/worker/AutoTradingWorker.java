package com.canagent.worker;

import com.canagent.domain.stock.Stock;
import com.canagent.repository.AnalysisScoreRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.analysis.CanSlimAnalysisService;
import com.canagent.service.analysis.CupAndHandleAnalyzer;
import com.canagent.service.dto.CanSlimResult;
import com.canagent.service.dto.CupAndHandleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Component
@ConditionalOnProperty(name = "trading.scheduler.enabled", havingValue = "true")
public class AutoTradingWorker {

    private static final Logger log = LoggerFactory.getLogger(AutoTradingWorker.class);

    private final StockRepository stockRepository;
    private final AnalysisScoreRepository analysisScoreRepository;
    private final CanSlimAnalysisService canSlimAnalysisService;
    private final CupAndHandleAnalyzer cupAndHandleAnalyzer;

    public AutoTradingWorker(
            StockRepository stockRepository,
            AnalysisScoreRepository analysisScoreRepository,
            CanSlimAnalysisService canSlimAnalysisService,
            CupAndHandleAnalyzer cupAndHandleAnalyzer) {
        this.stockRepository = stockRepository;
        this.analysisScoreRepository = analysisScoreRepository;
        this.canSlimAnalysisService = canSlimAnalysisService;
        this.cupAndHandleAnalyzer = cupAndHandleAnalyzer;
    }

    @Scheduled(cron = "${trading.scheduler.cron:0 0 19 * * MON-FRI}", zone = "Asia/Seoul")
    public void executeAnalysis() {
        log.info("===== 점수 저장 워커 시작 (전 종목 분석) =====");

        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        int savedCount = 0;

        try {
            var activeStocks = stockRepository.findByActiveTrue();
            log.info("대상 종목 수: {}", activeStocks.size());

            for (Stock stock : activeStocks) {
                try {
                    analyzeAndSave(stock, today);
                    savedCount++;
                } catch (Exception e) {
                    log.error("종목 분석 실패: {} ({}) - {}",
                            stock.getName(), stock.getCode(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("점수 저장 워커 실패: {}", e.getMessage());
        }

        log.info("===== 점수 저장 워커 종료: {}종목 분석 완료 =====", savedCount);
    }

    private void analyzeAndSave(Stock stock, LocalDate today) {
        CanSlimResult canSlimResult = canSlimAnalysisService.analyze(stock);
        CupAndHandleResult cupResult = cupAndHandleAnalyzer.analyze(stock);

        analysisScoreRepository.upsertScore(
                stock.getId(),
                today,
                "AUTO_TRADING",
                canSlimResult.totalScore().intValue(),
                canSlimResult.currentQuarterEarnings() != null ? canSlimResult.currentQuarterEarnings().score().intValue() : 0,
                canSlimResult.annualEarnings() != null ? canSlimResult.annualEarnings().score().intValue() : 0,
                canSlimResult.supplyDemand() != null ? canSlimResult.supplyDemand().score().intValue() : 0,
                canSlimResult.marketDirection() != null ? canSlimResult.marketDirection().score().intValue() : 0,
                canSlimResult.marketPosition() != null ? canSlimResult.marketPosition().score().intValue() : 0,
                0,
                cupResult.score() != null ? cupResult.score().intValue() : 0,
                cupResult.patternType() != null ? cupResult.patternType().name() : "NO_PATTERN",
                canSlimResult.totalScore().add(cupResult.score() != null ? cupResult.score() : BigDecimal.ZERO).intValue()
        );
    }

    public void runManualAnalysis() {
        log.info("수동 점수 저장 시작");
        executeAnalysis();
        log.info("수동 점수 저장 완료");
    }
}
