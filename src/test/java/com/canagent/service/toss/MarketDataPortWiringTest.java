package com.canagent.service.toss;

import com.canagent.port.MarketDataPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 (c): MarketDataPort 빈 단일화.
 * <ul>
 *   <li>주입되는 MarketDataPort는 TossMarketDataAdapter여야 한다(유일 구현).</li>
 * </ul>
 * (P9: 폐기된 KrxDataSyncService는 삭제됨 — "비-포트" 참조 검증 라인 제거.)
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MarketDataPort 빈 단일화 검증 (검증 c)")
class MarketDataPortWiringTest {

    @Autowired
    private MarketDataPort marketDataPort;

    @Test
    @DisplayName("유일 MarketDataPort 구현은 TossMarketDataAdapter")
    void portIsTossAdapter() {
        assertThat(marketDataPort).isInstanceOf(TossMarketDataAdapter.class);
    }
}
