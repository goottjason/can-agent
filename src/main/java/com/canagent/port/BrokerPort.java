package com.canagent.port;

import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.service.dto.KoreaInvestmentOrderResponse;
import com.canagent.service.dto.KoreaInvestmentPriceResponse;

/**
 * 브로커(주문·잔고·현재가) 경계 포트.
 *
 * <p>P1(미국 대전환): 어댑터 이음새 확보를 위한 순수 인터페이스 추출.
 * 현재 유일한 구현은 {@code KoreaInvestmentApiClient}이며, 시그니처·반환 DTO는 기존 KIS 동작 그대로다.
 * 미국용 재설계(placeBuy/OrderSpec, BigDecimal 가격 등)는 P2/P6 소관이므로 여기서 바꾸지 않는다.
 *
 * <p>호출부: TradingStrategyService(buy/sell/getBalance), IntradayMonitorWorker(getCurrentPrice/getBalance),
 * PortfolioScheduler(getCurrentPrice), DashboardController(getBalance).
 */
public interface BrokerPort {

    KoreaInvestmentOrderResponse buy(String stockCode, int quantity, int price);

    KoreaInvestmentOrderResponse sell(String stockCode, int quantity, int price);

    KoreaInvestmentBalanceResponse getBalance();

    KoreaInvestmentPriceResponse getCurrentPrice(String stockCode);
}
