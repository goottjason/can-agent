package com.canagent.web;

import com.canagent.config.ApiConfig;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import com.canagent.service.KoreaInvestmentApiClient;
import com.canagent.service.KrxDataSyncService;
import com.canagent.service.DartDataSyncService;
import com.canagent.service.PortfolioService;
import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.worker.AutoTradingWorker;
import com.canagent.worker.IntradayMonitorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
            ApiConfig apiConfig) {
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
        this.portfolioService = portfolioService;
        this.autoTradingWorker = autoTradingWorker;
        this.intradayMonitorWorker = intradayMonitorWorker;
        this.koreaInvestmentApiClient = koreaInvestmentApiClient;
        this.krxDataSyncService = krxDataSyncService;
        this.dartDataSyncService = dartDataSyncService;
        this.apiConfig = apiConfig;
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
    public java.util.Map<String, Object> getMonitorStatus() {
        boolean active = intradayMonitorWorker != null && intradayMonitorWorker.isMonitoring();
        java.time.LocalDateTime lastCheck = intradayMonitorWorker != null ? intradayMonitorWorker.getLastCheckTime() : null;
        int signalCount = intradayMonitorWorker != null ? intradayMonitorWorker.getLastSignalCount() : 0;
        java.util.List<java.util.Map<String, Object>> signals = intradayMonitorWorker != null ? intradayMonitorWorker.getLastSignals() : java.util.Collections.emptyList();

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("monitoring", active);
        result.put("lastCheckTime", lastCheck != null ? lastCheck.toString() : "");
        result.put("signalCount", signalCount);
        result.put("signals", signals);
        return result;
    }
}
