package com.canagent.service.edgar;

import com.canagent.port.FinancialsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 (b): FinancialsPort 빈 단일화.
 * <ul>
 *   <li>주입되는 FinancialsPort는 EdgarFinancialsAdapter여야 한다(유일 구현).</li>
 * </ul>
 * (P9: 폐기된 DartDataSyncService는 삭제됨 — "비-포트" 참조 검증 라인 제거.)
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("FinancialsPort 빈 단일화 검증 (검증 b)")
class FinancialsPortWiringTest {

    @Autowired
    private FinancialsPort financialsPort;

    @Test
    @DisplayName("유일 FinancialsPort 구현은 EdgarFinancialsAdapter")
    void portIsEdgarAdapter() {
        assertThat(financialsPort).isInstanceOf(EdgarFinancialsAdapter.class);
    }
}
