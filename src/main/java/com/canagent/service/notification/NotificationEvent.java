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
        // P2: %,d(longValue) 정수 절삭 제거 → USD 센트 유실 방지.
        // 정수부 자릿수 구분자는 유지(기존 KRW 동작 동일), 소수부는 유효 자릿수만 표기(무손실).
        // 통화기호("원"/"$")·로케일은 P8 표시층 소관이라 건드리지 않는다(숫자 포맷 타입만 decimal화).
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0");
        df.setMaximumFractionDigits(Math.max(0, price.scale()));
        return df.format(price);
    }
}
