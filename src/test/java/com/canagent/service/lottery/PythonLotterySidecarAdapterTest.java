package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.port.dto.SidecarResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("파이썬 사이드카 어댑터 JSON 파싱 단위테스트")
class PythonLotterySidecarAdapterTest {

    private final PythonLotterySidecarAdapter adapter =
            new PythonLotterySidecarAdapter(new LotteryConfig(), new ObjectMapper());

    @Test
    @DisplayName("정상 구매 JSON을 SidecarResult로 파싱한다")
    void parseSuccess() {
        String json = "{\"ok\":true,\"balanceAfter\":6000,\"tickets\":["
                + "{\"gameType\":\"LOTTO645\",\"roundNo\":1181,\"numbers\":\"3,7,12,25,33,41\",\"amount\":1000},"
                + "{\"gameType\":\"WIN720\",\"roundNo\":240,\"numbers\":\"3:123456\",\"amount\":1000}],"
                + "\"errors\":[]}";

        SidecarResult r = adapter.parseSidecarJson(json);

        assertThat(r.ok()).isTrue();
        assertThat(r.balanceAfter()).isEqualTo(6000);
        assertThat(r.tickets()).hasSize(2);
        assertThat(r.tickets().get(0).gameType()).isEqualTo(GameType.LOTTO645);
        assertThat(r.tickets().get(0).numbers()).isEqualTo("3,7,12,25,33,41");
        assertThat(r.errors()).isEmpty();
    }

    @Test
    @DisplayName("실패 JSON의 errors를 파싱한다")
    void parseError() {
        String json = "{\"ok\":false,\"balanceAfter\":6000,\"tickets\":[],"
                + "\"errors\":[{\"gameType\":\"WIN720\",\"reason\":\"로그인 실패\"}]}";

        SidecarResult r = adapter.parseSidecarJson(json);

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0).reason()).isEqualTo("로그인 실패");
    }
}
