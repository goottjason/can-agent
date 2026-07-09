package com.canagent.domain.stock;

/**
 * 재무보고 기간 유형.
 *
 * <p>US 대전환(P3~): SEC 공시 유형을 구분한다.
 * <ul>
 *   <li>{@link #QUARTER} — 분기보고(10-Q)</li>
 *   <li>{@link #ANNUAL} — 연간보고(10-K)</li>
 * </ul>
 *
 * <p>KR 기존 데이터는 이 값이 null이다(nullable). 분기/연간 판정은 여전히
 * {@code fiscalQuarter}에 의존하며, 분석기 lookup의 periodType 전환은 P4로 이관한다.
 */
public enum FiscalPeriodType {
    QUARTER,
    ANNUAL
}
