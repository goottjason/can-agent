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
 * <p>P5: {@code KrxDataSyncService.savePriceImmediately}가 쓰던 영속화 패턴(같은 (stock,date) 존재 시 skip,
 * 없으면 저장)을 소스별로 재사용 가능하게 추출했다. {@code TossMarketDataAdapter}가 종목별 캔들 upsert에 쓴다.
 *
 * <p>KrxDataSyncService는 자체 단위테스트({@code @InjectMocks}로 StockPriceRepository 직접 모킹)를
 * 무수정 유지해야 하므로 이 헬퍼로 리팩터하지 않고 내부 인라인 로직을 그대로 둔다(P9 정리 대상).
 *
 * <p>{@code REQUIRES_NEW}로 캔들 1건 단위 커밋 — 한 건 실패가 종목 전체 백필을 롤백시키지 않게 한다
 * (KRX 벌크 동기화의 검증된 패턴).
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
}
