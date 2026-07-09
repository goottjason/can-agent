package com.canagent.port;

import java.time.LocalDate;

/**
 * 시세 취득·적재 경계 포트.
 *
 * <p>P1(미국 대전환): 어댑터 이음새 확보를 위한 순수 인터페이스 추출.
 * 현재 유일한 구현은 {@code KrxDataSyncService}이며, 시그니처는 기존 KRX 동기화 동작 그대로다.
 * P1에서 디커플 대상인 호출부(DashboardController)가 실제로 의존하는 지점이 SyncService이므로
 * 여기에 최소 메서드만 올린다(fetch 루프 역전·종목별 캔들화는 P5 소관).
 *
 * <p>호출부: DashboardController.syncPrices().
 */
public interface MarketDataPort {

    int syncAllActiveStocks(LocalDate startDate, LocalDate endDate);
}
