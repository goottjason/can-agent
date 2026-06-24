package com.canagent.service.notification;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("알림 이벤트 단위테스트")
class NotificationEventTest {

    @Test
    @DisplayName("매수 거래로 이벤트를 생성한다")
    void fromTrade_buyTrade_createsEvent() {
        Stock stock = MockDataFactory.createSamsungStock();
        Trade trade = new Trade(stock, TradeType.BUY, 10, new BigDecimal("70000"), "테스트 매수");

        NotificationEvent event = NotificationEvent.fromTrade(trade);

        assertThat(event.tradeType()).isEqualTo(TradeType.BUY);
        assertThat(event.stockCode()).isEqualTo("005930");
        assertThat(event.stockName()).isEqualTo("삼성전자");
        assertThat(event.quantity()).isEqualTo(10);
        assertThat(event.price()).isEqualByComparingTo(new BigDecimal("70000"));
        assertThat(event.reason()).isEqualTo("테스트 매수");
    }

    @Test
    @DisplayName("매도 거래로 이벤트를 생성한다")
    void fromTrade_sellTrade_createsEvent() {
        Stock stock = MockDataFactory.createSamsungStock();
        Trade trade = new Trade(stock, TradeType.SELL, 10, new BigDecimal("80000"), "익절");
        trade.setProfitRate(new BigDecimal("14.29"));

        NotificationEvent event = NotificationEvent.fromTrade(trade);

        assertThat(event.tradeType()).isEqualTo(TradeType.SELL);
        assertThat(event.profitRate()).isEqualByComparingTo(new BigDecimal("14.29"));
    }

    @Test
    @DisplayName("매수 메시지를 포맷한다")
    void formatMessage_buyTrade_containsBuyInfo() {
        Stock stock = MockDataFactory.createSamsungStock();
        Trade trade = new Trade(stock, TradeType.BUY, 10, new BigDecimal("70000"), "테스트 매수");

        NotificationEvent event = NotificationEvent.fromTrade(trade);
        String message = event.formatMessage();

        assertThat(message).contains("매수");
        assertThat(message).contains("삼성전자");
        assertThat(message).contains("005930");
        assertThat(message).contains("10주");
        assertThat(message).contains("70,000원");
        assertThat(message).contains("테스트 매수");
    }

    @Test
    @DisplayName("매도 메시지에 수익률이 포함된다")
    void formatMessage_sellTrade_containsProfitRate() {
        Stock stock = MockDataFactory.createSamsungStock();
        Trade trade = new Trade(stock, TradeType.SELL, 10, new BigDecimal("80000"), "익절");
        trade.setProfitRate(new BigDecimal("14.29"));

        NotificationEvent event = NotificationEvent.fromTrade(trade);
        String message = event.formatMessage();

        assertThat(message).contains("매도");
        assertThat(message).contains("14.29%");
    }

    @Test
    @DisplayName("시간이 포함된다")
    void formatMessage_containsDateTime() {
        Stock stock = MockDataFactory.createSamsungStock();
        Trade trade = new Trade(stock, TradeType.BUY, 10, new BigDecimal("70000"), "테스트");

        NotificationEvent event = NotificationEvent.fromTrade(trade);
        String message = event.formatMessage();

        assertThat(message).contains("2026");
    }
}
