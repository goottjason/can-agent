package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;
import com.canagent.service.lottery.dto.Win720Draw;

import java.util.List;

public final class LotteryRankCalculator {

    private LotteryRankCalculator() {}

    /** 로또 등수(1~5), 미당첨 0. */
    public static int lottoRank(List<Integer> ticket, LottoDraw draw) {
        long m = ticket.stream().filter(draw.numbers()::contains).count();
        boolean bonus = ticket.contains(draw.bonus());
        if (m == 6) return 1;
        if (m == 5 && bonus) return 2;
        if (m == 5) return 3;
        if (m == 4) return 4;
        if (m == 3) return 5;
        return 0;
    }

    /** 연금 등수(1~7), 보너스 8, 미당첨 0. */
    public static int win720Rank(int jo, String digits, Win720Draw draw) {
        int s = commonSuffixLength(digits, draw.digits());
        if (s == 6 && jo == draw.jo()) return 1;
        if (s == 6) return 2;
        if (digits.equals(draw.bonusDigits())) return 8;   // 보너스
        if (s == 5) return 3;
        if (s == 4) return 4;
        if (s == 3) return 5;
        if (s == 2) return 6;
        if (s == 1) return 7;
        return 0;
    }

    private static int commonSuffixLength(String a, String b) {
        int i = a.length() - 1, j = b.length() - 1, n = 0;
        while (i >= 0 && j >= 0 && a.charAt(i) == b.charAt(j)) { i--; j--; n++; }
        return n;
    }
}
