package com.canagent.service.toss;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.port.MarketDataPort;
import com.canagent.repository.StockRepository;
import com.canagent.service.StockPriceUpserter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 토스 캔들 기반 {@link MarketDataPort} 구현 — <b>유일한 MarketDataPort 빈</b>.
 *
 * <p>DashboardController(P1부터 포트 주입)·DataSyncScheduler(P5에서 포트로 전환)의 MarketDataPort 주입은
 * 이 어댑터를 사용한다(시그니처 불변). (구)KRX 동기화 클래스는 P9에서 삭제됨.
 *
 * <p><b>fetch 루프 역전</b>: KRX는 "날짜별 전종목 배치"였으나, 토스 캔들은 종목별 조회이므로
 * "종목별 캔들 페이지네이션"으로 뒤집는다. active + ticker 보유 종목 각각에 대해 최신부터
 * {@code before} 커서로 과거로 백필하며 {@code startDate}에 도달하면 멈춘다.
 *
 * <p>영속화(생성·중복체크·저장)는 {@link StockPriceUpserter}로 공유.
 */
@Service
public class TossMarketDataAdapter implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(TossMarketDataAdapter.class);

    /** 종목당 페이지네이션 안전 상한(무한루프 방지). 200개/페이지 × 40 ≈ 8000거래일 ≫ 65주. */
    private static final int MAX_PAGES_PER_STOCK = 40;

    private final TossCandleClient candleClient;
    private final StockRepository stockRepository;
    private final StockPriceUpserter stockPriceUpserter;

    public TossMarketDataAdapter(TossCandleClient candleClient,
                                 StockRepository stockRepository,
                                 StockPriceUpserter stockPriceUpserter) {
        this.candleClient = candleClient;
        this.stockRepository = stockRepository;
        this.stockPriceUpserter = stockPriceUpserter;
    }

    /**
     * active + ticker 보유 종목의 [startDate, endDate] 일봉을 캔들 페이지네이션으로 백필·upsert한다.
     *
     * <p>ticker 없는 종목은 카운터+debug 로그를 남기고 skip(무로그 early-return 금지).
     * 반환값은 신규 저장된 StockPrice 건수(이미 있던 날짜는 미포함).
     */
    @Override
    public int syncAllActiveStocks(LocalDate startDate, LocalDate endDate) {
        List<Stock> activeStocks = stockRepository.findByActiveTrue();
        if (activeStocks.isEmpty()) {
            log.info("토스 시세 동기화: 활성 종목 없음");
            return 0;
        }

        int totalSaved = 0;
        int skippedNoTicker = 0;
        for (Stock stock : activeStocks) {
            String ticker = stock.getTicker();
            if (ticker == null || ticker.isBlank()) {
                skippedNoTicker++;
                log.debug("토스 시세 skip(ticker 없음): {} ({})", stock.getName(), stock.getCode());
                continue;
            }
            try {
                totalSaved += syncStock(stock, ticker, startDate, endDate);
            } catch (Exception e) {
                log.warn("토스 시세 동기화 실패: {} (ticker {}) - {}", stock.getName(), ticker, e.getMessage());
            }
        }

        log.info("토스 시세 동기화 완료: {}건 저장 (ticker 없음 {}건 skip, {}~{})",
                totalSaved, skippedNoTicker, startDate, endDate);
        return totalSaved;
    }

    /**
     * 한 종목을 최신부터 startDate까지 캔들 페이지네이션하며 upsert. 저장 건수 반환.
     *
     * <p>토스 캔들은 최신→과거 정렬. endDate 이후(더 최신)는 건너뛰고, startDate 이전(더 과거)에 닿으면
     * 백필 종료. {@code before} 커서는 응답 {@code nextBefore}를 그대로 되돌려 보낸다(불투명 토큰).
     */
    private int syncStock(Stock stock, String ticker, LocalDate startDate, LocalDate endDate) {
        int saved = 0;
        String before = null; // 첫 페이지: 최신부터
        int pages = 0;

        while (pages < MAX_PAGES_PER_STOCK) {
            pages++;
            TossCandlePage page = candleClient.getDailyCandles(ticker, TossCandleClient.MAX_COUNT, before);
            if (page == null || page.getCandles().isEmpty()) {
                break;
            }

            boolean reachedStart = false;
            for (TossCandle c : page.getCandles()) {
                LocalDate d = c.getDate();
                if (d.isAfter(endDate)) {
                    continue; // 조회창보다 최신 — 저장 안 함
                }
                if (d.isBefore(startDate)) {
                    reachedStart = true; // startDate 이전 도달 — 백필 종료
                    break;
                }
                StockPrice sp = stockPriceUpserter.upsert(
                        stock, d, c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume(), null);
                if (sp != null) {
                    saved++;
                }
            }

            if (reachedStart || !page.hasMore()) {
                break;
            }
            before = page.getNextBefore();
        }

        if (pages >= MAX_PAGES_PER_STOCK) {
            log.warn("토스 시세: {} 페이지 상한({}) 도달 — 백필 조기 종료", ticker, MAX_PAGES_PER_STOCK);
        }
        log.debug("토스 시세 종목 완료: {} {}건 저장 ({}페이지)", ticker, saved, pages);
        return saved;
    }
}
