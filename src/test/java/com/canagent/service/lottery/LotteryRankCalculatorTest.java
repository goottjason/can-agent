package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;
import com.canagent.service.lottery.dto.Win720Draw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("복권 등수·상금 계산 단위테스트")
class LotteryRankCalculatorTest {

    private final LottoDraw lotto = new LottoDraw(1100, List.of(3, 7, 12, 25, 33, 41), 10, 2_000_000_000L, true);

    @Test
    @DisplayName("로또 1등: 6개 일치")
    void lottoFirst() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 12, 25, 33, 41), lotto)).isEqualTo(1);
    }

    @Test
    @DisplayName("로또 2등: 5개+보너스")
    void lottoSecond() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 12, 25, 33, 10), lotto)).isEqualTo(2);
    }

    @Test
    @DisplayName("로또 3등: 5개(보너스 없음)")
    void lottoThird() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 12, 25, 33, 44), lotto)).isEqualTo(3);
    }

    @Test
    @DisplayName("로또 5등: 3개 일치")
    void lottoFifth() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 12, 44, 45, 2), lotto)).isEqualTo(5);
    }

    @Test
    @DisplayName("로또 미당첨: 2개 일치")
    void lottoNone() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 1, 2, 44, 45), lotto)).isEqualTo(0);
    }

    private final Win720Draw win = new Win720Draw(240, 3, "123456", "987654", true);

    @Test
    @DisplayName("연금 1등: 조+6자리")
    void winFirst() {
        assertThat(LotteryRankCalculator.win720Rank(3, "123456", win)).isEqualTo(1);
    }

    @Test
    @DisplayName("연금 2등: 조 다르고 6자리 일치")
    void winSecond() {
        assertThat(LotteryRankCalculator.win720Rank(2, "123456", win)).isEqualTo(2);
    }

    @Test
    @DisplayName("연금 3등: 뒤 5자리 일치")
    void winThird() {
        assertThat(LotteryRankCalculator.win720Rank(1, "923456", win)).isEqualTo(3);
    }

    @Test
    @DisplayName("연금 7등: 뒤 1자리 일치")
    void winSeventh() {
        assertThat(LotteryRankCalculator.win720Rank(1, "999996", win)).isEqualTo(7);
    }

    @Test
    @DisplayName("연금 보너스: 보너스번호 6자리 일치")
    void winBonus() {
        assertThat(LotteryRankCalculator.win720Rank(1, "987654", win)).isEqualTo(8);
    }

    @Test
    @DisplayName("상금 라벨: 로또 1등은 당첨금 포함")
    void prizeLabels() {
        assertThat(LotteryPrizeFormatter.lotto(1, lotto)).contains("1등");
        assertThat(LotteryPrizeFormatter.lotto(5, lotto)).contains("5등");
        assertThat(LotteryPrizeFormatter.lotto(0, lotto)).isEqualTo("미당첨");
        assertThat(LotteryPrizeFormatter.win720(1)).contains("1등");
        assertThat(LotteryPrizeFormatter.win720(8)).contains("보너스");
        assertThat(LotteryPrizeFormatter.win720(0)).isEqualTo("미당첨");
    }
}
