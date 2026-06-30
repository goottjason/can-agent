package com.canagent.service.notification;

import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public record NotificationEvent(
        TradeType tradeType,
        String stockCode,
        String stockName,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal totalAmount,
        BigDecimal profitRate,
        String reason,
        LocalDateTime tradeDateTime
) {

    public static NotificationEvent fromTrade(Trade trade) {
        return new NotificationEvent(
                trade.getTradeType(),
                trade.getStock().getCode(),
                trade.getStock().getName(),
                trade.getQuantity(),
                trade.getPrice(),
                trade.getTotalAmount(),
                trade.getProfitRate(),
                trade.getReason(),
                trade.getTradeDateTime()
        );
    }

    public String formatMessage() {
        String typeStr = tradeType == TradeType.BUY ? "매수" : "매도";
        String emoji = tradeType == TradeType.BUY ? "\uD83D\uDD34" : "\uD83D\uDD35";

        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" ").append(typeStr).append(" 알림\n\n");
        sb.append("\u2022 종목: ").append(stockName).append(" (").append(stockCode).append(")\n");
        sb.append("\u2022 수량: ").append(quantity).append("주\n");
        sb.append("\u2022 가격: ").append(formatPrice(price)).append("원\n");
        sb.append("\u2022 금액: ").append(formatPrice(totalAmount)).append("원\n");

        if (tradeType == TradeType.SELL && profitRate != null) {
            sb.append("\u2022 수익률: ").append(profitRate.setScale(2, BigDecimal.ROUND_HALF_UP)).append("%\n");
        }

        sb.append("\u2022 사유: ").append(reason != null ? reason : "N/A\n");
        sb.append("\u2022 시간: ").append(tradeDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        return sb.toString();
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "0";
        return String.format("%,d", price.longValue());
    }
}
