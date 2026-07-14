package com.canagent.service;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.StockPriceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * StockPrice 생성·중복체크·저장 공유 헬퍼.
 *
 * <p>P5에서 (구)KRX 일별 동기화가 쓰던 영속화 패턴(같은 (stock,date) 존재 시 skip, 없으면 저장)을
 * 소스별로 재사용 가능하게 추출했다. {@code TossMarketDataAdapter}가 종목별 캔들 upsert에 쓴다.
 * (P9: KRX 동기화 클래스는 삭제됨 — 이 헬퍼가 유일한 upsert 경로.)
 *
 * <p>{@code REQUIRES_NEW}로 캔들 1건 단위 커밋 — 한 건 실패가 종목 전체 백필을 롤백시키지 않게 한다.
 */
@Component
public class StockPriceUpserter {

    private final StockPriceRepository stockPriceRepository;

    public StockPriceUpserter(StockPriceRepository stockPriceRepository) {
        this.stockPriceRepository = stockPriceRepository;
    }

    /**
     * (stock, date)가 이미 있으면 null(skip), 없으면 새 StockPrice 저장 후 반환.
     *
     * @param changeRate 등락률(없으면 null — 캔들 소스는 미제공 가능)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StockPrice upsert(Stock stock, LocalDate date,
                             BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                             Long volume, BigDecimal changeRate) {
        Optional<StockPrice> existing = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(stock.getId(), date, date)
                .stream()
                .findFirst();
        if (existing.isPresent()) {
            return null;
        }

        StockPrice stockPrice = new StockPrice(stock, date, open, high, low, close,
                volume == null ? 0L : volume);
        if (changeRate != null) {
            stockPrice.setChangeRate(changeRate);
        }
        return stockPriceRepository.save(stockPrice);
    }

    /**
     * 장중 스팟 현재가를 (stock, date)에 멱등하게 반영한다.
     *
     * <p>행이 없으면 O=H=L=C=현재가·volume 0 임시행을 INSERT, 있으면 그 행의
     * {@link StockPrice#applyIntradaySpot(BigDecimal)}로 close 갱신·high/low 확장(open·volume 보존).
     * 매 사이클 재-INSERT로 인한 유니크 위반을 제거한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StockPrice upsertIntradaySpot(Stock stock, LocalDate date, BigDecimal currentPrice) {
        Optional<StockPrice> existing = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(stock.getId(), date, date)
                .stream()
                .findFirst();
        if (existing.isPresent()) {
            StockPrice sp = existing.get();
            sp.applyIntradaySpot(currentPrice);
            return stockPriceRepository.save(sp);
        }
        StockPrice stockPrice = new StockPrice(stock, date,
                currentPrice, currentPrice, currentPrice, currentPrice, 0L);
        return stockPriceRepository.save(stockPrice);
    }
}
