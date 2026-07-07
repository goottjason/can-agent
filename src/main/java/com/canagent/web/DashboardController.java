package com.canagent.web;

import com.canagent.config.ApiConfig;
import com.canagent.domain.analysis.AnalysisScore;
import com.canagent.domain.analysis.MonitorCheckLog;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.AnalysisScoreRepository;
import com.canagent.repository.MonitorCheckLogRepository;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import com.canagent.service.KoreaInvestmentApiClient;
import com.canagent.service.KrxDataSyncService;
import com.canagent.service.DartDataSyncService;
import com.canagent.service.PortfolioService;
import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.worker.AutoTradingWorker;
import com.canagent.worker.IntradayMonitorWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;
    private final PortfolioService portfolioService;
    private final AutoTradingWorker autoTradingWorker;
    private final IntradayMonitorWorker intradayMonitorWorker;
    private final KoreaInvestmentApiClient koreaInvestmentApiClient;
    private final KrxDataSyncService krxDataSyncService;
    private final DartDataSyncService dartDataSyncService;
    private final ApiConfig apiConfig;
    private final MonitorCheckLogRepository monitorCheckLogRepository;
    private final AnalysisScoreRepository analysisScoreRepository;
    private final ObjectMapper objectMapper;

    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");
    private static final int CHECK_INTERVAL_SECONDS = 300; // monitor-cron = 0 */5 9-15 * * MON-FRI
    private static final int STALE_THRESHOLD_SECONDS = 360; // 검사 6분 초과 시 '지연'

    @Value("${trading.min-score:120}")
    private int minScore;

    @Autowired
    public DashboardController(
            PortfolioRepository portfolioRepository,
            TradeRepository tradeRepository,
            PortfolioService portfolioService,
            @Autowired(required = false) AutoTradingWorker autoTradingWorker,
            @Autowired(required = false) IntradayMonitorWorker intradayMonitorWorker,
            KoreaInvestmentApiClient koreaInvestmentApiClient,
            KrxDataSyncService krxDataSyncService,
            DartDataSyncService dartDataSyncService,
            ApiConfig apiConfig,
            MonitorCheckLogRepository monitorCheckLogRepository,
            AnalysisScoreRepository analysisScoreRepository,
            ObjectMapper objectMapper) {
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
        this.portfolioService = portfolioService;
        this.autoTradingWorker = autoTradingWorker;
        this.intradayMonitorWorker = intradayMonitorWorker;
        this.koreaInvestmentApiClient = koreaInvestmentApiClient;
        this.krxDataSyncService = krxDataSyncService;
        this.dartDataSyncService = dartDataSyncService;
        this.apiConfig = apiConfig;
        this.monitorCheckLogRepository = monitorCheckLogRepository;
        this.analysisScoreRepository = analysisScoreRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Portfolio> portfolios = portfolioService.getActivePortfolios();
        List<Trade> recentTrades = tradeRepository.findAllByOrderByTradeDateTimeDesc();

        BigDecimal totalInvestment = portfolioService.getTotalInvestment();
        BigDecimal totalCurrentValue = portfolioService.getTotalCurrentValue();
        BigDecimal totalProfit = portfolioService.getTotalProfit();
        BigDecimal totalProfitRate = portfolioService.getTotalProfitRate();

        Map<String, Object> tradeStats = portfolioService.getTradeStatistics();
        Map<String, Object> riskStatus = portfolioService.getRiskStatus();

        // 한국투자증권 계좌 잔고 조회
        String accountNumber = apiConfig.getKoreaInvestment().getAccountNumber();
        long totalAssetAmount = 0;
        long availableCashAmount = 0;
        boolean accountConnected = false;

        try {
            KoreaInvestmentBalanceResponse balanceResponse = koreaInvestmentApiClient.getBalance();
            if (balanceResponse != null && balanceResponse.isSuccess() && balanceResponse.getOutput2() != null
                    && !balanceResponse.getOutput2().isEmpty()) {
                KoreaInvestmentBalanceResponse.AccountSummary summary = balanceResponse.getOutput2().get(0);
                totalAssetAmount = parseLongSafe(summary.getTotalAssetAmount());
                availableCashAmount = parseLongSafe(summary.getAvailableCashAmount());
                accountConnected = true;
            }
        } catch (Exception e) {
            log.warn("계좌 잔고 조회 실패: {}", e.getMessage());
        }

        model.addAttribute("portfolios", portfolios);
        model.addAttribute("recentTrades", recentTrades.stream().limit(10).toList());
        model.addAttribute("totalInvestment", totalInvestment);
        model.addAttribute("totalCurrentValue", totalCurrentValue);
        model.addAttribute("totalProfit", totalProfit);
        model.addAttribute("totalProfitRate", totalProfitRate);
        model.addAttribute("portfolioCount", portfolios.size());
        model.addAttribute("buyCount", tradeStats.get("totalBuyCount"));
        model.addAttribute("sellCount", tradeStats.get("totalSellCount"));
        model.addAttribute("winRate", tradeStats.get("winRate"));
        model.addAttribute("availableSlots", portfolioService.getAvailableSlots());
        model.addAttribute("maxPositions", riskStatus.get("maxPositions"));
        model.addAttribute("isMaxPositions", riskStatus.get("isMaxPositions"));
        model.addAttribute("isLowCash", riskStatus.get("isLowCash"));
        model.addAttribute("lossCount", riskStatus.get("lossCount"));
        model.addAttribute("schedulerEnabled", autoTradingWorker != null);

        // 계좌 정보
        model.addAttribute("accountNumber", accountNumber != null ? accountNumber : "미설정");
        model.addAttribute("totalAssetAmount", totalAssetAmount);
        model.addAttribute("availableCashAmount", availableCashAmount);
        model.addAttribute("accountConnected", accountConnected);

        // 모니터링 정보
        boolean monitorActive = intradayMonitorWorker != null && intradayMonitorWorker.isMonitoring();
        model.addAttribute("monitorActive", monitorActive);
        model.addAttribute("monitorLastCheck", intradayMonitorWorker != null ? intradayMonitorWorker.getLastCheckTime() : null);
        model.addAttribute("monitorSignalCount", intradayMonitorWorker != null ? intradayMonitorWorker.getLastSignalCount() : 0);
        model.addAttribute("monitorSignals", intradayMonitorWorker != null ? intradayMonitorWorker.getLastSignals() : java.util.Collections.emptyList());
        model.addAttribute("activeMenu", "dashboard");

        return "dashboard";
    }

    // ========== 시스템 소개 페이지 ==========

    @GetMapping("/system/overview")
    public String systemOverview(Model model) {
        model.addAttribute("activeMenu", "overview");
        return "system-overview";
    }

    @GetMapping("/system/monitoring")
    public String systemMonitoring(Model model) {
        model.addAttribute("activeMenu", "monitoring");
        return "system-monitoring";
    }

    @GetMapping("/system/canslim")
    public String systemCanslim(Model model) {
        model.addAttribute("activeMenu", "canslim");
        return "system-canslim";
    }

    @GetMapping("/system/cup-handle")
    public String systemCupHandle(Model model) {
        model.addAttribute("activeMenu", "cup-handle");
        return "system-cup-handle";
    }

    @GetMapping("/system/trading")
    public String systemTrading(Model model) {
        model.addAttribute("activeMenu", "trading");
        return "system-trading";
    }

    private long parseLongSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @PostMapping("/trade/run")
    public String runManualCheck(org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        if (autoTradingWorker != null) {
            autoTradingWorker.runManualAnalysis();
            redirectAttributes.addFlashAttribute("tradeResult", "수동 분석 완료 (점수 저장)");
        }
        return "redirect:/";
    }

    @PostMapping("/sync/prices")
    public String syncPrices(org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        try {
            java.time.LocalDate today = java.time.LocalDate.now();
            int count = krxDataSyncService.syncAllActiveStocks(today.minusDays(7), today);
            redirectAttributes.addFlashAttribute("tradeResult", "가격 동기화 완료: " + count + "건 저장");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("tradeResult", "가격 동기화 실패: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/sync/financials")
    public String syncFinancials(org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("tradeResult", "재무제표 동기화 시작 (백그라운드에서 실행 중)");
        new Thread(() -> {
            try {
                dartDataSyncService.syncAllActiveStocks("2025", "3");
                dartDataSyncService.syncAllActiveStocks("2024", "3");
                dartDataSyncService.syncAllActiveStocks("2024", "4");
            } catch (Exception e) {
                log.error("재무제표 동기화 실패: {}", e.getMessage());
            }
        }).start();
        return "redirect:/";
    }

    @PostMapping("/sync/import-financials")
    public String importFinancials(org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        try {
            int count = dartDataSyncService.importFromJsonFile("/app/dart_financials.json");
            redirectAttributes.addFlashAttribute("tradeResult", "재무제표 임포트 완료: " + count + "건 저장");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("tradeResult", "재무제표 임포트 실패: " + e.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/api/monitor/status")
    @ResponseBody
    public Map<String, Object> getMonitorStatus() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(KST);
        boolean tradingHours = isTradingHours(now);
        boolean monitoring = intradayMonitorWorker != null && intradayMonitorWorker.isMonitoring();

        MonitorCheckLog latest = monitorCheckLogRepository.findTopByOrderByCheckTimeDesc().orElse(null);
        java.time.LocalDateTime lastCheck = latest != null ? latest.getCheckTime()
                : (intradayMonitorWorker != null ? intradayMonitorWorker.getLastCheckTime() : null);

        Long secondsSinceLastCheck = lastCheck != null
                ? java.time.Duration.between(lastCheck, now).getSeconds() : null;

        // 상태 3단계 판정 (R1)
        String status;
        if (!tradingHours) {
            status = "CLOSED";
        } else if (secondsSinceLastCheck == null || secondsSinceLastCheck > STALE_THRESHOLD_SECONDS) {
            status = "STALE";
        } else {
            status = "RUNNING";
        }

        Integer nextCheckInSeconds = null;
        if (tradingHours) {
            int into = (now.getMinute() % 5) * 60 + now.getSecond();
            nextCheckInSeconds = CHECK_INTERVAL_SECONDS - into;
        }

        java.time.LocalDate today = now.toLocalDate();
        long todayCheckCount = monitorCheckLogRepository.countByCheckTimeBetween(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("serverTime", now.toString());
        result.put("tradingHours", tradingHours);
        result.put("checkIntervalSeconds", CHECK_INTERVAL_SECONDS);
        result.put("monitoring", monitoring);
        result.put("status", status);
        result.put("lastCheckTime", lastCheck != null ? lastCheck.toString() : "");
        result.put("secondsSinceLastCheck", secondsSinceLastCheck);
        result.put("nextCheckInSeconds", nextCheckInSeconds);
        result.put("todayCheckCount", todayCheckCount);
        result.put("lastScanCount", latest != null ? latest.getScanned()
                : (intradayMonitorWorker != null ? intradayMonitorWorker.getLastScanCount() : 0));
        result.put("minScore", minScore);

        // 퍼널 카운트 (R3)
        Map<String, Object> funnel = new java.util.LinkedHashMap<>();
        funnel.put("scanned", latest != null ? latest.getScanned() : 0);
        funnel.put("priceFail", latest != null ? latest.getPriceFailCount() : 0);
        funnel.put("heldSkip", latest != null ? latest.getHeldSkipCount() : 0);
        funnel.put("signalMiss", latest != null ? latest.getSignalMissCount() : 0);
        funnel.put("scoreMiss", latest != null ? latest.getScoreMissCount() : 0);
        funnel.put("signals", latest != null ? latest.getSignalCount() : 0);
        funnel.put("orderSuccess", latest != null ? latest.getOrderSuccessCount() : 0);
        funnel.put("orderBlocked", latest != null ? latest.getOrderBlockedCount() : 0);
        result.put("funnel", funnel);

        // 상위 미달 종목 + 신호 실행 결과 (R3·R4) — 영속 JSON을 구조화해 반환 (재시작 후에도 유지)
        result.put("nearMiss", parseJsonArray(latest != null ? latest.getNearMissJson() : null));
        result.put("signals", parseJsonArray(latest != null ? latest.getExecutionJson() : null));

        // 점수 상위 리더보드 (R2)
        result.put("leaderboard", buildLeaderboard());

        return result;
    }

    private List<Map<String, Object>> buildLeaderboard() {
        java.time.LocalDate latestDate = analysisScoreRepository.findLatestAnalysisDate().orElse(null);
        if (latestDate == null) {
            return java.util.Collections.emptyList();
        }
        List<AnalysisScore> top = analysisScoreRepository.findTopByAnalysisDate(latestDate, PageRequest.of(0, 20));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (AnalysisScore a : top) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("code", a.getStock().getCode());
            m.put("name", a.getStock().getName());
            m.put("analysisDate", a.getAnalysisDate().toString());
            m.put("source", a.getSource());
            m.put("totalScore", a.getTotalScore());
            m.put("canSlimScore", a.getCanSlimScore());
            m.put("cupScore", a.getCupScore());
            m.put("cupPattern", a.getCupPattern());
            m.put("quarterly", a.getQuarterlyScore());
            m.put("annual", a.getAnnualScore());
            m.put("supplyDemand", a.getSupplyDemandScore());
            m.put("marketDirection", a.getMarketDirectionScore());
            m.put("industryLeader", a.getIndustryLeaderScore());
            m.put("institutional", a.getInstitutionalScore());
            m.put("cup", a.getCupScore());
            m.put("passed", a.getTotalScore() >= minScore);
            m.put("shortBy", Math.max(0, minScore - a.getTotalScore()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("모니터 로그 JSON 파싱 실패: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private boolean isTradingHours(java.time.LocalDateTime kstNow) {
        java.time.DayOfWeek dow = kstNow.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) {
            return false;
        }
        java.time.LocalTime t = kstNow.toLocalTime();
        return !t.isBefore(java.time.LocalTime.of(9, 0)) && !t.isAfter(java.time.LocalTime.of(15, 30));
    }
}
