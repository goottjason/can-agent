package com.canagent.port;

import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.service.dto.KoreaInvestmentOrderResponse;
import com.canagent.service.dto.KoreaInvestmentPriceResponse;

import java.math.BigDecimal;

/**
 * 브로커(주문·잔고·현재가) 경계 포트.
 *
 * <p>P1(미국 대전환): 어댑터 이음새 확보를 위한 순수 인터페이스 추출.
 * 현재 유일한 구현은 {@code KoreaInvestmentApiClient}이다.
 *
 * <p>P2(미국 대전환): 가격은 {@code BigDecimal}로 무손실 전달한다(USD 센트 유실 방지).
 * KRW 정수 호가는 KIS 구현체 내부에서만 정수로 변환하며, 포트 계약은 소수 가격을 그대로 전달한다.
 * 수량(quantity)은 이번 단계에서 정수 유지(소수 수량·notional 사이징은 P6). 주문모델 재설계(placeBuy/OrderSpec)도 P6.
 *
 * <p>호출부: TradingStrategyService(buy/sell/getBalance), IntradayMonitorWorker(getCurrentPrice/getBalance),
 * PortfolioScheduler(getCurrentPrice), DashboardController(getBalance).
 */
public interface BrokerPort {

    KoreaInvestmentOrderResponse buy(String stockCode, int quantity, BigDecimal price);

    KoreaInvestmentOrderResponse sell(String stockCode, int quantity, BigDecimal price);

    KoreaInvestmentBalanceResponse getBalance();

    KoreaInvestmentPriceResponse getCurrentPrice(String stockCode);
}
