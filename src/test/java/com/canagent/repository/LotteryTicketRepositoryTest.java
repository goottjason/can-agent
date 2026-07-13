package com.canagent.repository;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("복권 티켓 리포지토리 단위테스트")
class LotteryTicketRepositoryTest {

    @Autowired
    private LotteryTicketRepository repository;

    @Test
    @DisplayName("이번 주 구매분 존재 여부를 게임별로 판정한다")
    void existsByGameTypeAndPurchasedAtAfter() {
        LocalDateTime weekStart = LocalDateTime.of(2026, 7, 13, 0, 0);
        repository.save(new LotteryTicket(GameType.LOTTO645, 1181, "3,7,12,25,33,41", 1000,
                LocalDateTime.of(2026, 7, 14, 9, 0)));

        assertThat(repository.existsByGameTypeAndPurchasedAtAfter(GameType.LOTTO645, weekStart)).isTrue();
        assertThat(repository.existsByGameTypeAndPurchasedAtAfter(GameType.WIN720, weekStart)).isFalse();
    }

    @Test
    @DisplayName("미확인 티켓을 게임별로 조회한다")
    void findByGameTypeAndResultCheckedFalse() {
        LotteryTicket t = new LotteryTicket(GameType.WIN720, 240, "3:123456", 1000, LocalDateTime.now());
        repository.save(t);

        List<LotteryTicket> pending = repository.findByGameTypeAndResultCheckedFalse(GameType.WIN720);
        assertThat(pending).hasSize(1);

        pending.get(0).applyResult(0, "미당첨");
        repository.save(pending.get(0));
        assertThat(repository.findByGameTypeAndResultCheckedFalse(GameType.WIN720)).isEmpty();
    }
}
