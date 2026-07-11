package com.canagent.port;

/**
 * 재무 취득·적재 경계 포트.
 *
 * <p>P1(미국 대전환): 어댑터 이음새 확보를 위한 순수 인터페이스 추출.
 * <p>P4: 유일한 구현이 {@code EdgarFinancialsAdapter}로 단일화됐다(SEC EDGAR companyfacts 기반).
 * (P9: 폐기된 DART 동기화 클래스는 삭제됨.)
 * 시그니처는 기존 계약 그대로라 호출부(DashboardController, DataSyncScheduler)는 무변경으로 EDGAR를 사용한다.
 *
 * <p>호출부: DashboardController.syncFinancials()/importFinancials(), DataSyncScheduler.syncQuarterlyFinancials().
 */
public interface FinancialsPort {

    int syncAllActiveStocks(String year, String quarter);

    int importFromJsonFile(String filePath);
}
