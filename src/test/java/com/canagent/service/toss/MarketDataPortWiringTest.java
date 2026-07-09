package com.canagent.service.toss;

import com.canagent.port.MarketDataPort;
import com.canagent.service.KrxDataSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 (c): MarketDataPort 빈 단일화.
 * <ul>
 *   <li>주입되는 MarketDataPort는 TossMarketDataAdapter여야 한다.</li>
 *   <li>KrxDataSyncService는 더 이상 MarketDataPort가 아니다(빈은 존재).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MarketDataPort 빈 단일화 검증 (검증 c)")
class MarketDataPortWiringTest {

    @Autowired
    private MarketDataPort marketDataPort;

    @Autowired
    private KrxDataSyncService krxDataSyncService;

    @Test
    @DisplayName("유일 MarketDataPort 구현은 TossMarketDataAdapter")
    void portIsTossAdapter() {
        assertThat(marketDataPort).isInstanceOf(TossMarketDataAdapter.class);
    }

    @Test
    @DisplayName("KrxDataSyncService는 MarketDataPort가 아니다")
    void krxIsNotPort() {
        assertThat(krxDataSyncService).isNotInstanceOf(MarketDataPort.class);
    }
}
