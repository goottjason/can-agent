package com.canagent.web;

import com.canagent.domain.stock.Stock;
import com.canagent.service.StockService;
import com.canagent.worker.DataSyncScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(StockController.class);

    private final StockService stockService;
    private final DataSyncScheduler dataSyncScheduler;

    @Autowired
    public StockController(StockService stockService,
                           @Autowired(required = false) DataSyncScheduler dataSyncScheduler) {
        this.stockService = stockService;
        this.dataSyncScheduler = dataSyncScheduler;
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
        model.addAttribute("kospiCount", stockService.getStockCountByMarket("KOSPI"));
        model.addAttribute("kosdaqCount", stockService.getStockCountByMarket("KOSDAQ"));
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

    @PostMapping("/sync")
    public String syncStockList(RedirectAttributes redirectAttributes) {
        try {
            int count = stockService.syncStockListFromKrx();
            redirectAttributes.addFlashAttribute("successMessage", "KRX 종목 리스트 동기화 완료: " + count + "건 신규 등록");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "동기화 실패: " + e.getMessage());
        }
        return "redirect:/stocks";
    }

    @PostMapping("/preset")
    public String registerPresetStocks(RedirectAttributes redirectAttributes) {
        try {
            int count = stockService.registerPresetStocks();
            redirectAttributes.addFlashAttribute("successMessage", "인기 종목 " + count + "건 등록 완료");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "등록 실패: " + e.getMessage());
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
                "kospi", stockService.getStockCountByMarket("KOSPI"),
                "kosdaq", stockService.getStockCountByMarket("KOSDAQ")
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

    @PostMapping("/sync/sectors")
    @ResponseBody
    public ResponseEntity<Map<String, String>> syncSectors() {
        new Thread(() -> {
            try {
                int count = stockService.syncSectorsFromDart();
                log.info("업종 동기화 완료: {}건 업데이트", count);
            } catch (Exception e) {
                log.error("업종 동기화 실패: {}", e.getMessage());
            }
        }).start();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "업종 동기화가 백그라운드에서 시작되었습니다."
        ));
    }
}
