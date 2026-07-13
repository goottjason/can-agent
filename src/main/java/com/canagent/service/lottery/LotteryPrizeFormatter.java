package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;

import java.text.DecimalFormat;

public final class LotteryPrizeFormatter {

    private static final DecimalFormat WON = new DecimalFormat("#,##0");

    private LotteryPrizeFormatter() {}

    public static String lotto(int rank, LottoDraw draw) {
        return switch (rank) {
            case 1 -> "1등 (" + WON.format(draw.firstWinAmount()) + "원)";
            case 2 -> "2등";
            case 3 -> "3등";
            case 4 -> "4등 (50,000원)";
            case 5 -> "5등 (5,000원)";
            default -> "미당첨";
        };
    }

    public static String win720(int rank) {
        return switch (rank) {
            case 1 -> "1등 (월 700만원 × 20년)";
            case 2 -> "2등 (월 100만원 × 10년)";
            case 3 -> "3등 (100만원)";
            case 4 -> "4등 (10만원)";
            case 5 -> "5등 (5만원)";
            case 6 -> "6등 (5천원)";
            case 7 -> "7등 (1천원)";
            case 8 -> "보너스 (월 100만원 × 10년)";
            default -> "미당첨";
        };
    }
}
