package com.canagent.port;

import java.time.LocalDate;

/**
 * 시세 취득·적재 경계 포트.
 *
 * <p>P1(미국 대전환): 어댑터 이음새 확보를 위한 순수 인터페이스 추출.
 * P5에서 <b>유일 구현이 {@code TossMarketDataAdapter}</b>로 확정됐다(토스 캔들 페이지네이션 백필).
 * (P9: 폐기된 KRX 동기화 클래스는 삭제됨.)
 *
 * <p>fetch 루프는 KRX의 "날짜별 전종목 배치"에서 토스의 "종목별 캔들 페이지네이션"으로 역전됐다.
 *
 * <p>호출부: DashboardController.syncPrices(), DataSyncScheduler.syncDailyData/runBulkPriceSync.
 */
public interface MarketDataPort {

    int syncAllActiveStocks(LocalDate startDate, LocalDate endDate);
}
