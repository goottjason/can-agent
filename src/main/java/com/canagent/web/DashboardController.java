package com.canagent.web;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import com.canagent.service.PortfolioService;
import com.canagent.worker.AutoTradingWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Controller
public class DashboardController {

    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;
    private final PortfolioService portfolioService;
    private final AutoTradingWorker autoTradingWorker;

    @Autowired
    public DashboardController(
            PortfolioRepository portfolioRepository,
            TradeRepository tradeRepository,
            PortfolioService portfolioService,
            @Autowired(required = false) AutoTradingWorker autoTradingWorker) {
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
        this.portfolioService = portfolioService;
        this.autoTradingWorker = autoTradingWorker;
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

        return "dashboard";
    }

    @PostMapping("/trade/run")
    public String runManualCheck() {
        if (autoTradingWorker != null) {
            autoTradingWorker.runManualCheck();
        }
        return "redirect:/";
    }
}
