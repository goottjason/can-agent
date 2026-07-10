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
import com.canagent.port.BrokerPort;
import com.canagent.port.MarketDataPort;
import com.canagent.port.FinancialsPort;
import com.canagent.port.dto.BrokerBalance;
import com.canagent.service.MarketHours;
import com.canagent.service.PortfolioService;
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
    private final BrokerPort koreaInvestmentApiClient;
    private final MarketDataPort krxDataSyncService;
    private final FinancialsPort dartDataSyncService;
    private final ApiConfig apiConfig;
    private final MonitorCheckLogRepository monitorCheckLogRepository;
    private final AnalysisScoreRepository analysisScoreRepository;
    private final ObjectMapper objectMapper;
    private final MarketHours marketHours;

    // 미국장 기준(P7). serverTime·장중 판정은 ET(America/New_York). 프론트 표시(monitor.js)는 클라이언트 로케일.
    private static final java.time.ZoneId ET = java.time.ZoneId.of("America/New_York");
    private static final int CHECK_INTERVAL_SECONDS = 300; // monitor-cron(ET) = 0 */5 9-15 * * MON-FRI
    private static final int STALE_THRESHOLD_SECONDS = 360; // 검사 6분 초과 시 '지연'

    // #1 계좌 "API 연결 실패" 완화: 대시보드는 SSR로 매 로드마다 잔고를 호출한다.
    // 한투 잔고조회 유량제한/순단으로 단발 실패 시 즉시 "연결 실패"로 뒤집히던 것을
    // 단기 캐시 + 재시도 + 마지막 성공값 폴백으로 견고화한다. (읽기 표시 경로 전용 — 워커 매매 경로 불변)
    private static final long BALANCE_CACHE_TTL_MS = 5_000;
    private volatile BrokerBalance cachedBalance;
    private volatile long cachedBalanceAt;

    @Value("${trading.min-score:120}")
    private int minScore;

    @Autowired
    public DashboardController(
            PortfolioRepository portfolioRepository,
            TradeRepository tradeRepository,
            PortfolioService portfolioService,
            @Autowired(required = false) AutoTradingWorker autoTradingWorker,
            @Autowired(required = false) IntradayMonitorWorker intradayMonitorWorker,
            BrokerPort koreaInvestmentApiClient,
            MarketDataPort krxDataSyncService,
            FinancialsPort dartDataSyncService,
            ApiConfig apiConfig,
            MonitorCheckLogRepository monitorCheckLogRepository,
            AnalysisScoreRepository analysisScoreRepository,
            ObjectMapper objectMapper,
            MarketHours marketHours) {
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
        this.marketHours = marketHours;
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

        // P6: 포트가 broker-중립 BrokerBalance를 반환. 표시 모델 속성명(totalAssetAmount/availableCashAmount)과
        // long 타입은 종전과 동일 → dashboard.html 바인딩 무변경.
        BrokerBalance balance = fetchBalanceResilient();
        if (balance != null && balance.success()) {
            totalAssetAmount = toLong(balance.totalEval());
            availableCashAmount = toLong(balance.availableCash());
            accountConnected = true;
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

    /**
     * 대시보드 표시용 잔고를 견고하게 조회한다.
     * 1) TTL 내 캐시가 있으면 그대로 사용(연속 새로고침으로 유량제한 유발 방지),
     * 2) 없으면 최대 2회 재시도하며 실패 시 rt_cd/msg 원인을 로깅,
     * 3) 전부 실패하면 마지막 성공 잔고(있으면)를 폴백 반환 — 단발 순단으로 "연결 실패"가 뒤집히는 것을 막는다.
     * 매매 판단이 아닌 관측성 경로이므로 소폭 stale 값 허용.
     */
    private BrokerBalance fetchBalanceResilient() {
        long now = System.currentTimeMillis();
        BrokerBalance cache = cachedBalance;
        if (cache != null && now - cachedBalanceAt < BALANCE_CACHE_TTL_MS) {
            return cache;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                BrokerBalance res = koreaInvestmentApiClient.getBalance();
                if (res != null && res.success()) {
                    cachedBalance = res;
                    cachedBalanceAt = System.currentTimeMillis();
                    return res;
                }
                log.warn("계좌 잔고 조회 비정상 응답 (시도 {}/2): {}",
                        attempt, res != null ? res.message() : "null");
            } catch (Exception e) {
                log.warn("계좌 잔고 조회 예외 (시도 {}/2): {}", attempt, e.getMessage());
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 모든 시도 실패 — 마지막 성공 잔고라도 반환(없으면 null → "연결 실패" 표기)
        return cachedBalance;
    }

    private long toLong(BigDecimal value) {
        return value != null ? value.setScale(0, RoundingMode.FLOOR).longValue() : 0L;
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
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ET);
        boolean tradingHours = marketHours.isTradingHours();
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

}
