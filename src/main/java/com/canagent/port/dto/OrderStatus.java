package com.canagent.port.dto;

import java.math.BigDecimal;

/**
 * 주문 체결 상태(broker-중립) — 상세조회 결과.
 *
 * <p>P6(미국 대전환): 토스 주문상세(status/체결수량/평균체결가/수수료/세금)와 KIS 체결조회를 흡수한다.
 * status enum은 토스 실사(B §1) 값집합을 그대로 채택한다(PENDING/PARTIAL_FILLED/PENDING_CANCEL/
 * PENDING_REPLACE/CLOSED). 알 수 없는 값은 {@link Status#UNKNOWN}으로 견고 매핑.
 *
 * @param orderId      브로커 주문번호
 * @param status       체결 상태
 * @param filledQty    체결 수량
 * @param avgFillPrice 평균 체결가
 * @param commission   수수료
 * @param tax          세금(해외 양도세 등 — 브로커 제공 시)
 * @param message      조회 실패/경고 사유(성공 시 null 가능)
 */
public record OrderStatus(
        String orderId,
        Status status,
        BigDecimal filledQty,
        BigDecimal avgFillPrice,
        BigDecimal commission,
        BigDecimal tax,
        String message
) {

    /** 토스 주문 상태(B실사 §1 값집합). */
    public enum Status {
        PENDING,
        PARTIAL_FILLED,
        PENDING_CANCEL,
        PENDING_REPLACE,
        CLOSED,
        UNKNOWN
    }

    /** 조회 실패. status=UNKNOWN, 사유를 담는다. */
    public static OrderStatus failure(String orderId, String message) {
        return new OrderStatus(orderId, Status.UNKNOWN, BigDecimal.ZERO, null, null, null, message);
    }
}
