package com.canagent.service.edgar;

import com.canagent.port.FinancialsPort;
import com.canagent.service.DartDataSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 (b): FinancialsPort 빈 단일화.
 * <ul>
 *   <li>주입되는 FinancialsPort는 EdgarFinancialsAdapter여야 한다.</li>
 *   <li>DartDataSyncService는 더 이상 FinancialsPort가 아니다(빈은 존재).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("FinancialsPort 빈 단일화 검증 (검증 b)")
class FinancialsPortWiringTest {

    @Autowired
    private FinancialsPort financialsPort;

    @Autowired
    private DartDataSyncService dartDataSyncService;

    @Test
    @DisplayName("유일 FinancialsPort 구현은 EdgarFinancialsAdapter")
    void portIsEdgarAdapter() {
        assertThat(financialsPort).isInstanceOf(EdgarFinancialsAdapter.class);
    }

    @Test
    @DisplayName("DartDataSyncService는 FinancialsPort가 아니다")
    void dartIsNotPort() {
        assertThat(dartDataSyncService).isNotInstanceOf(FinancialsPort.class);
    }
}
