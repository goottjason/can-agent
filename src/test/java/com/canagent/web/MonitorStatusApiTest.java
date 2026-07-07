package com.canagent.web;

import com.canagent.domain.analysis.MonitorCheckLog;
import com.canagent.repository.MonitorCheckLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/**
 * /api/monitor/status 런타임 JSON shape 검증.
 * nearMiss/signals 필드가 실제 DB 데이터(MonitorCheckLog)에서 올바르게 파싱되는지 확인.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("모니터 상태 API shape 검증")
class MonitorStatusApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MonitorCheckLogRepository monitorCheckLogRepository;

    // NearMiss/ExecutionResult 레코드가 Jackson으로 직렬화될 때 쓰이는 camelCase 키 그대로 사용
    private static final String SAMPLE_NEAR_MISS_JSON =
            "[{\"code\":\"005930\",\"name\":\"삼성전자\",\"totalScore\":115," +
            "\"canSlimScore\":48,\"cupScore\":67,\"quarterly\":12,\"annual\":10," +
            "\"supplyDemand\":8,\"marketDirection\":10,\"industryLeader\":8," +
            "\"institutional\":0,\"stage\":\"score\",\"reason\":\"minScore 미달\"}]";

    private static final String SAMPLE_EXECUTION_JSON =
            "[{\"code\":\"000660\",\"name\":\"SK하이닉스\",\"price\":180000," +
            "\"canSlimScore\":52,\"cupScore\":75,\"totalScore\":127," +
            "\"reason\":\"CUP 패턴+CANSLIM\",\"status\":\"ORDER_SUCCESS\",\"detail\":\"1주 매수완료\"}]";

    @BeforeEach
    void setUp() {
        monitorCheckLogRepository.deleteAll();
        MonitorCheckLog log = new MonitorCheckLog(
                LocalDateTime.now().minusMinutes(2),
                380, 12, 3, 350, 9, 1, 1, 0, 120,
                SAMPLE_NEAR_MISS_JSON, SAMPLE_EXECUTION_JSON);
        monitorCheckLogRepository.save(log);
    }

    @Test
    @DisplayName("status API가 200을 반환하고 최상위 필드가 모두 존재한다")
    void topLevelFieldsPresent() throws Exception {
        mockMvc.perform(get("/api/monitor/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.lastCheckTime").exists())
                .andExpect(jsonPath("$.todayCheckCount").exists())
                .andExpect(jsonPath("$.lastScanCount").value(380))
                .andExpect(jsonPath("$.minScore").exists())
                .andExpect(jsonPath("$.funnel").exists())
                .andExpect(jsonPath("$.nearMiss").exists())
                .andExpect(jsonPath("$.signals").exists())
                .andExpect(jsonPath("$.leaderboard").exists());
    }

    @Test
    @DisplayName("status 값이 RUNNING, STALE, CLOSED 중 하나다")
    void statusIsValidEnum() throws Exception {
        mockMvc.perform(get("/api/monitor/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status",
                        anyOf(is("RUNNING"), is("STALE"), is("CLOSED"))));
    }

    @Test
    @DisplayName("funnel 8개 키가 모두 존재한다")
    void funnelHasAllEightKeys() throws Exception {
        mockMvc.perform(get("/api/monitor/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funnel.scanned").value(380))
                .andExpect(jsonPath("$.funnel.priceFail").value(12))
                .andExpect(jsonPath("$.funnel.heldSkip").value(3))
                .andExpect(jsonPath("$.funnel.signalMiss").value(350))
                .andExpect(jsonPath("$.funnel.scoreMiss").value(9))
                .andExpect(jsonPath("$.funnel.signals").value(1))
                .andExpect(jsonPath("$.funnel.orderSuccess").value(1))
                .andExpect(jsonPath("$.funnel.orderBlocked").value(0));
    }

    @Test
    @DisplayName("nearMiss 배열에 기대 필드(code/name/totalScore/quarterly/annual/supplyDemand/marketDirection/industryLeader/institutional/reason)가 모두 있다")
    void nearMissHasExpectedFields() throws Exception {
        mockMvc.perform(get("/api/monitor/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearMiss").isArray())
                .andExpect(jsonPath("$.nearMiss", hasSize(1)))
                .andExpect(jsonPath("$.nearMiss[0].code").value("005930"))
                .andExpect(jsonPath("$.nearMiss[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.nearMiss[0].totalScore").value(115))
                .andExpect(jsonPath("$.nearMiss[0].quarterly").value(12))
                .andExpect(jsonPath("$.nearMiss[0].annual").value(10))
                .andExpect(jsonPath("$.nearMiss[0].supplyDemand").value(8))
                .andExpect(jsonPath("$.nearMiss[0].marketDirection").value(10))
                .andExpect(jsonPath("$.nearMiss[0].industryLeader").value(8))
                .andExpect(jsonPath("$.nearMiss[0].institutional").value(0))
                .andExpect(jsonPath("$.nearMiss[0].reason").value("minScore 미달"));
    }

    @Test
    @DisplayName("signals 배열에 기대 필드(code/name/price/canSlimScore/cupScore/totalScore/status/detail)가 모두 있다")
    void signalsHasExpectedFields() throws Exception {
        mockMvc.perform(get("/api/monitor/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals").isArray())
                .andExpect(jsonPath("$.signals", hasSize(1)))
                .andExpect(jsonPath("$.signals[0].code").value("000660"))
                .andExpect(jsonPath("$.signals[0].name").value("SK하이닉스"))
                .andExpect(jsonPath("$.signals[0].price").value(180000))
                .andExpect(jsonPath("$.signals[0].canSlimScore").value(52))
                .andExpect(jsonPath("$.signals[0].cupScore").value(75))
                .andExpect(jsonPath("$.signals[0].totalScore").value(127))
                .andExpect(jsonPath("$.signals[0].status").value("ORDER_SUCCESS"))
                .andExpect(jsonPath("$.signals[0].detail").value("1주 매수완료"));
    }

    @Test
    @DisplayName("MonitorCheckLog 없을 때 nearMiss/signals/funnel이 빈 배열/0으로 반환된다")
    void emptyResponseWhenNoLog() throws Exception {
        monitorCheckLogRepository.deleteAll();

        mockMvc.perform(get("/api/monitor/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearMiss").isArray())
                .andExpect(jsonPath("$.nearMiss", hasSize(0)))
                .andExpect(jsonPath("$.signals").isArray())
                .andExpect(jsonPath("$.signals", hasSize(0)))
                .andExpect(jsonPath("$.funnel.scanned").value(0))
                .andExpect(jsonPath("$.funnel.orderSuccess").value(0));
    }
}
