package com.canagent.web;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import com.canagent.worker.AutoTradingWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Controller
public class DashboardController {

    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;
    private final AutoTradingWorker autoTradingWorker;

    @Autowired
    public DashboardController(
            PortfolioRepository portfolioRepository,
            TradeRepository tradeRepository,
            @Autowired(required = false) AutoTradingWorker autoTradingWorker) {
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
        this.autoTradingWorker = autoTradingWorker;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Portfolio> portfolios = portfolioRepository.findByActiveTrue();
        List<Trade> recentTrades = tradeRepository.findAllByOrderByTradeDateTimeDesc();

        BigDecimal totalInvestment = portfolios.stream()
                .map(p -> p.getTotalBuyAmount() != null ? p.getTotalBuyAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCurrentValue = portfolios.stream()
                .map(p -> {
                    BigDecimal price = p.getCurrentPrice() != null ? p.getCurrentPrice() : p.getAverageBuyPrice();
                    return price.multiply(new BigDecimal(p.getQuantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalProfit = totalCurrentValue.subtract(totalInvestment);
        BigDecimal totalProfitRate = totalInvestment.compareTo(BigDecimal.ZERO) > 0
                ? totalProfit.divide(totalInvestment, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        long buyCount = recentTrades.stream()
                .filter(t -> t.getTradeType() == TradeType.BUY)
                .count();
        long sellCount = recentTrades.stream()
                .filter(t -> t.getTradeType() == TradeType.SELL)
                .count();

        model.addAttribute("portfolios", portfolios);
        model.addAttribute("recentTrades", recentTrades.stream().limit(10).toList());
        model.addAttribute("totalInvestment", totalInvestment);
        model.addAttribute("totalCurrentValue", totalCurrentValue);
        model.addAttribute("totalProfit", totalProfit);
        model.addAttribute("totalProfitRate", totalProfitRate);
        model.addAttribute("portfolioCount", portfolios.size());
        model.addAttribute("buyCount", buyCount);
        model.addAttribute("sellCount", sellCount);
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
