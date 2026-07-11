package com.canagent.web;

import com.canagent.domain.stock.Stock;
import com.canagent.service.StockService;
import com.canagent.service.edgar.SecTickerUniverseLoader;
import com.canagent.worker.DataSyncScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/stocks")
public class StockController {

    private final StockService stockService;
    private final DataSyncScheduler dataSyncScheduler;
    private final SecTickerUniverseLoader universeLoader;

    @Autowired
    public StockController(StockService stockService,
                           @Autowired(required = false) DataSyncScheduler dataSyncScheduler,
                           SecTickerUniverseLoader universeLoader) {
        this.stockService = stockService;
        this.dataSyncScheduler = dataSyncScheduler;
        this.universeLoader = universeLoader;
    }

    @GetMapping
    public String stockList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String market,
            Model model) {
        List<Stock> stocks;
        if (keyword != null && !keyword.isBlank()) {
            stocks = stockService.searchAllStocks(keyword);
        } else if (market != null && !market.isBlank()) {
            stocks = stockService.getStocksByMarket(market);
        } else {
            stocks = stockService.getAllStocks();
        }

        model.addAttribute("stocks", stocks);
        model.addAttribute("keyword", keyword);
        model.addAttribute("market", market);
        model.addAttribute("totalActiveCount", stockService.getActiveStockCount());
        // P8: 미국 대전환. Stock.market에는 US 종목의 거래소명(NYSE/NASDAQ, SecTickerUniverseLoader가 exchange를
        // market에 기록)이 담긴다. 국내 KOSPI/KOSDAQ 카운트를 미국 거래소 카운트로 교체(표시 수준, 쿼리는 market 기반 그대로).
        model.addAttribute("nyseCount", stockService.getStockCountByMarket("NYSE"));
        model.addAttribute("nasdaqCount", stockService.getStockCountByMarket("NASDAQ"));
        model.addAttribute("activeMenu", "stocks");

        return "stock-list";
    }

    @GetMapping("/{id}")
    public String stockDetail(@PathVariable Long id, Model model) {
        Stock stock = stockService.getStockById(id)
                .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다: " + id));
        model.addAttribute("stock", stock);
        model.addAttribute("activeMenu", "stocks");
        return "stock-detail";
    }

    @PostMapping
    public String registerStock(
            @RequestParam String code,
            @RequestParam String name,
            @RequestParam String market,
            @RequestParam(required = false) String sector,
            RedirectAttributes redirectAttributes) {
        try {
            stockService.registerStock(code, name, market, sector != null ? sector : market);
            redirectAttributes.addFlashAttribute("successMessage", "종목이 등록되었습니다: " + name);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/stocks";
    }

    @PostMapping("/{id}/deactivate")
    public String deactivateStock(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            stockService.deactivateStock(id);
            redirectAttributes.addFlashAttribute("successMessage", "종목이 비활성화되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/stocks";
    }

    @PostMapping("/{id}/activate")
    public String activateStock(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            stockService.activateStock(id);
            redirectAttributes.addFlashAttribute("successMessage", "종목이 활성화되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/stocks";
    }

    @GetMapping("/api/search")
    @ResponseBody
    public ResponseEntity<List<Stock>> searchStocksApi(@RequestParam String keyword) {
        List<Stock> stocks = stockService.searchStocks(keyword);
        return ResponseEntity.ok(stocks);
    }

    @GetMapping("/api/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "totalActive", stockService.getActiveStockCount(),
                "nyse", stockService.getStockCountByMarket("NYSE"),
                "nasdaq", stockService.getStockCountByMarket("NASDAQ")
        ));
    }

    /**
     * 미국 유니버스 적재. 클래스패스의 {@code company_tickers.json}(SEC 대형주)을 읽어
     * ticker/cik/exchange를 Stock으로 upsert한다. market에는 거래소명(NYSE/NASDAQ)이 기록된다.
     * P9에서 제거된 KRX {@code /sync}의 미국판 대체. 반환은 upsert 건수.
     */
    @PostMapping("/load-universe")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> loadUniverse() {
        int upserted = universeLoader.loadFromClasspath();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "upserted", upserted,
                "nyse", stockService.getStockCountByMarket("NYSE"),
                "nasdaq", stockService.getStockCountByMarket("NASDAQ"),
                "message", "미국 유니버스 적재 완료: " + upserted + "종목"
        ));
    }

    @PostMapping("/sync/bulk-prices")
    @ResponseBody
    public ResponseEntity<Map<String, String>> bulkPriceSync(
            @RequestParam(defaultValue = "200") int days) {
        new Thread(() -> dataSyncScheduler.runBulkPriceSync(days)).start();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "벌크 주가 동기화가 백그라운드에서 시작되었습니다: " + days + " 거래일"
        ));
    }

    @PostMapping("/sync/bulk-financials")
    @ResponseBody
    public ResponseEntity<Map<String, String>> bulkFinancialSync(
            @RequestParam(defaultValue = "8") int quarters) {
        new Thread(() -> dataSyncScheduler.runBulkFinancialSync(quarters)).start();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "벌크 재무 동기화가 백그라운드에서 시작되었습니다: " + quarters + "분기"
        ));
    }

}
