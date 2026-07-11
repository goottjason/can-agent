package com.canagent.port.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 계좌 잔고(broker-중립).
 *
 * <p>P6(미국 대전환): KIS {@code output2}(예수금·평가)·{@code output1}(보유목록) 및 토스
 * {@code buying-power}(cashBuyingPower)+{@code holdings}(P6b PoC 확정) 응답을
 * 이 타입으로 흡수한다. 사이징은 {@link #availableCash()}(주문 가능 현금)를 기준으로 한다(E §3).
 *
 * <p>필드명은 시장중립: KIS는 KRW 예수금(출금가능금액), 토스 US는 USD 현금을 담는다.
 *
 * @param success       조회 성공 여부(실패 시 사이징 게이트가 사유를 남긴다 — 조용실패 방지)
 * @param availableCash 주문 가능 현금(KIS 출금가능금액 / 토스 USD 현금). 실패 시 0.
 * @param totalEval     총 평가금액(현금+주식). 표시용.
 * @param holdings      보유 종목 목록(표시·매도 판단 보조).
 * @param message       실패/경고 사유(성공 시 null 가능).
 */
public record BrokerBalance(
        boolean success,
        BigDecimal availableCash,
        BigDecimal totalEval,
        List<Holding> holdings,
        String message
) {

    /** 보유 종목 1건(broker-중립). 수량·평단·평가금액. */
    public record Holding(
            String symbol,
            String name,
            BigDecimal quantity,
            BigDecimal avgBuyPrice,
            BigDecimal evalAmount
    ) {}

    /** 조회 실패. availableCash=0, 사유를 담는다. */
    public static BrokerBalance failure(String message) {
        return new BrokerBalance(false, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), message);
    }
}
