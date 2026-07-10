package com.canagent.port.dto;

import java.math.BigDecimal;

/**
 * 주문(생성·정정·취소) 결과(broker-중립).
 *
 * <p>P6(미국 대전환): KIS {@code KoreaInvestmentOrderResponse}·토스 주문응답을 이 타입으로 흡수한다.
 * 호출부는 {@link #success()}·{@link #message()}만 보고 재시도/알림을 결정한다(기존 로직 이식).
 *
 * @param success       주문 접수 성공 여부(체결이 아니라 접수 성공)
 * @param orderId       브로커 주문번호(실패 시 null 가능)
 * @param message       실패 사유·성공 메시지(무로그 조용실패 방지 — 실패 시 사유를 담는다)
 * @param filledQty     체결 수량(접수 시점엔 0/미상 가능, 상세조회로 갱신)
 * @param avgFillPrice  평균 체결가(접수 시점엔 null 가능)
 */
public record OrderResult(
        boolean success,
        String orderId,
        String message,
        BigDecimal filledQty,
        BigDecimal avgFillPrice
) {

    /** 접수 성공(주문번호·메시지만). 체결정보는 상세조회로 별도 확보. */
    public static OrderResult accepted(String orderId, String message) {
        return new OrderResult(true, orderId, message, BigDecimal.ZERO, null);
    }

    /** 실패. 사유를 반드시 담는다(조용한 0건 방지). */
    public static OrderResult failure(String message) {
        return new OrderResult(false, null, message, BigDecimal.ZERO, null);
    }
}
