package com.canagent.port;

/**
 * 재무 취득·적재 경계 포트.
 *
 * <p>P1(미국 대전환): 어댑터 이음새 확보를 위한 순수 인터페이스 추출.
 * 현재 유일한 구현은 {@code DartDataSyncService}이며, 시그니처는 기존 DART 동기화 동작 그대로다.
 * P1에서 디커플 대상인 호출부(DashboardController)가 실제로 의존하는 지점이 SyncService이므로
 * 여기에 최소 메서드만 올린다(EDGAR/XBRL·CIK 기반 재조회는 P4 소관).
 *
 * <p>호출부: DashboardController.syncFinancials() / importFinancials().
 */
public interface FinancialsPort {

    int syncAllActiveStocks(String year, String quarter);

    int importFromJsonFile(String filePath);
}
