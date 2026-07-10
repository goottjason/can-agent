package com.canagent.port.dto;

import java.math.BigDecimal;

/**
 * 주문 명세(broker-중립). {@code sealed}로 두 경로만 허용한다.
 *
 * <p>P6(미국 대전환): 소수점 우선 설계(E §3). 1차 경로는 금액기반 시장가 매수({@link Notional}),
 * 정수·지정가는 부차({@link Limit}). 어댑터는 이 명세를 각 브로커 요청 필드로 매핑한다.
 * <ul>
 *   <li>{@link Notional} = 소수점 시장가: 주문 금액(orderAmount)만 전달(수량·가격 없음). 토스 US 소수 매수.
 *   <li>{@link Limit} = 정수·지정가: 수량·가격 전달. KIS 국내 경로·토스 정수 지정가.
 * </ul>
 */
public sealed interface OrderSpec permits OrderSpec.Notional, OrderSpec.Limit {

    /** 금액기반 시장가(소수 수량 자동 라우팅). {@code orderAmount}는 통화 그대로(USD/KRW). */
    record Notional(BigDecimal orderAmount) implements OrderSpec {}

    /** 정수·지정가. {@code qty}·{@code price}는 BigDecimal 무손실(가격 USD 센트 보존). */
    record Limit(BigDecimal qty, BigDecimal price) implements OrderSpec {}

    /** 금액기반 시장가 명세 생성 편의. */
    static Notional notional(BigDecimal orderAmount) {
        return new Notional(orderAmount);
    }

    /** 정수·지정가 명세 생성 편의. */
    static Limit limit(BigDecimal qty, BigDecimal price) {
        return new Limit(qty, price);
    }
}
