package com.canagent.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CupAndHandleResult(
        String stockCode,
        String stockName,
        PatternType patternType,
        BigDecimal score,
        LocalDate cupStartDate,
        LocalDate cupEndDate,
        BigDecimal cupDepthPercent,
        LocalDate handleStartDate,
        LocalDate handleEndDate,
        BigDecimal handleDepthPercent,
        BigDecimal breakoutPrice,
        BigDecimal targetPrice,
        boolean isBuySignal,
        String reason
) {

    public enum PatternType {
        NONE,
        CUP_FORMING,
        HANDLE_FORMING,
        HANDLE_COMPLETE,
        BREAKOUT
    }

    public static CupAndHandleResult noPattern(String stockCode, String stockName) {
        return new CupAndHandleResult(
                stockCode,
                stockName,
                PatternType.NONE,
                BigDecimal.ZERO,
                null, null, null,
                null, null, null,
                null, null,
                false,
                "패턴 없음"
        );
    }
}
