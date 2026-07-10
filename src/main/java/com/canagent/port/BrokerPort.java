package com.canagent.port;

import com.canagent.port.dto.BrokerBalance;
import com.canagent.port.dto.OrderResult;
import com.canagent.port.dto.OrderSpec;
import com.canagent.port.dto.OrderStatus;

import java.math.BigDecimal;

/**
 * 브로커(주문·잔고·현재가) 경계 포트 — broker-중립.
 *
 * <p>P6(미국 대전환): KIS DTO 반환 계약을 broker-중립 값타입으로 재설계했다(E §1.1).
 * 구현: {@code KoreaInvestmentApiClient}(@Primary, 국내·정수·KRW), {@code TossBrokerAdapter}(프로퍼티/프로필로 활성,
 * 미국·소수·USD). 각 어댑터가 내부에서 자사 DTO를 {@link OrderResult}/{@link BrokerBalance}/{@link OrderStatus}로 매핑한다.
 *
 * <p>주문 명세는 {@link OrderSpec}(sealed): {@code Notional}(금액기반 시장가 — 소수 매수 1차 경로) /
 * {@code Limit}(정수·지정가). KIS는 Limit만 지원(Notional은 미지원 사유 담아 실패 반환), 토스는 둘 다.
 *
 * <p>호출부: TradingStrategyService(placeBuy/placeSell/getBalance), IntradayMonitorWorker(getCurrentPrice/getBalance),
 * PortfolioScheduler(getCurrentPrice), DashboardController(getBalance).
 */
public interface BrokerPort {

    /** 매수. {@code Notional}=금액 시장가, {@code Limit}=수량·지정가. */
    OrderResult placeBuy(String symbol, OrderSpec spec);

    /** 매도. 소수 수량 시장가({@code Notional}은 매도에서 전량/부분 청산 의미) 또는 지정가. */
    OrderResult placeSell(String symbol, OrderSpec spec);

    /** 미체결 주문 정정(가격·수량). */
    OrderResult modify(String orderId, OrderSpec spec);

    /** 주문 취소. */
    OrderResult cancel(String orderId);

    /** 계좌 잔고(주문 가능 현금·평가·보유). */
    BrokerBalance getBalance();

    /** 주문 체결/부분체결 상세 조회. */
    OrderStatus getOrder(String orderId);

    /** 현재가(무손실 BigDecimal — USD 센트 보존). 실패 시 0 반환. */
    BigDecimal getCurrentPrice(String symbol);
}
