package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.port.dto.SidecarResult;
import com.canagent.port.dto.SidecarResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

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

    @Test
    @DisplayName("sidecar-command 미설정이면 오류 SidecarResult를 반환한다(크래시 없음)")
    void emptyCommand_returnsErrorResult() {
        LotteryConfig cfg = new LotteryConfig();   // sidecarCommand 기본 빈 리스트
        PythonLotterySidecarAdapter a = new PythonLotterySidecarAdapter(cfg, new com.fasterxml.jackson.databind.ObjectMapper());
        SidecarResult r = a.purchaseWeekly(java.util.List.of(GameType.LOTTO645));
        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).isNotEmpty();
    }

    @Test
    @DisplayName("result JSON을 SidecarResults로 파싱한다(WIN/LOSE 혼재)")
    void parseResults() {
        String json = "{\"ok\":true,\"results\":["
                + "{\"gameType\":\"WIN720\",\"roundNo\":324,\"status\":\"LOSE\",\"winAmount\":0,\"drawDate\":\"2026-07-18\"},"
                + "{\"gameType\":\"LOTTO645\",\"roundNo\":1233,\"status\":\"WIN\",\"winAmount\":5000,\"drawDate\":\"2026-07-18\"}],"
                + "\"errors\":[]}";

        SidecarResults r = adapter.parseResultsJson(json);

        assertThat(r.ok()).isTrue();
        assertThat(r.results()).hasSize(2);
        assertThat(r.results().get(0).gameType()).isEqualTo(GameType.WIN720);
        assertThat(r.results().get(0).status()).isEqualTo("LOSE");
        assertThat(r.results().get(1).gameType()).isEqualTo(GameType.LOTTO645);
        assertThat(r.results().get(1).status()).isEqualTo("WIN");
        assertThat(r.results().get(1).winAmount()).isEqualTo(5000);
        assertThat(r.errors()).isEmpty();
    }

    @Test
    @DisplayName("빈 결과 result JSON도 정상 파싱한다")
    void parseEmptyResults() {
        String json = "{\"ok\":true,\"results\":[],\"errors\":[]}";

        SidecarResults r = adapter.parseResultsJson(json);

        assertThat(r.ok()).isTrue();
        assertThat(r.results()).isEmpty();
        assertThat(r.errors()).isEmpty();
    }

    @Test
    @DisplayName("ok=false result JSON의 errors를 파싱한다")
    void parseResultsError() {
        String json = "{\"ok\":false,\"results\":[],"
                + "\"errors\":[{\"gameType\":\"ALL\",\"reason\":\"결과조회 차단\"}]}";

        SidecarResults r = adapter.parseResultsJson(json);

        assertThat(r.ok()).isFalse();
        assertThat(r.results()).isEmpty();
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0).reason()).isEqualTo("결과조회 차단");
    }

    @Test
    @DisplayName("깨진 result JSON은 크래시 없이 오류 SidecarResults를 반환한다")
    void parseResultsMalformed() {
        SidecarResults r = adapter.parseResultsJson("not json");

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).isNotEmpty();
    }

    /** 멈춘 사이드카가 스케줄러 스레드를 영구 점유하지 않는지 — 출력 미종료 상태에서 타임아웃 강제. */
    @Test
    @Timeout(15)
    @DisplayName("사이드카가 응답 없이 매달려도 타임아웃으로 회수하고 오류 결과를 반환한다")
    void hangingSidecarTimesOut() {
        LotteryConfig cfg = new LotteryConfig();
        cfg.setSidecarCommand(List.of("sh", "-c", "sleep 30"));   // 출력 없이 매달림
        cfg.setSidecarTimeoutSec(2);
        PythonLotterySidecarAdapter a = new PythonLotterySidecarAdapter(cfg, new ObjectMapper());

        long t0 = System.currentTimeMillis();
        SidecarResult r = a.purchaseWeekly(List.of(GameType.WIN720));
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.reason().contains("타임아웃"));
        assertThat(elapsed).isLessThan(10_000);
    }

    @Test
    @Timeout(15)
    @DisplayName("마지막 줄 JSON만 계약으로 읽는다(앞선 로그 줄은 무시)")
    void readsLastLineAsJson() {
        LotteryConfig cfg = new LotteryConfig();
        cfg.setSidecarCommand(List.of("sh", "-c",
                "echo '[WIN720] 시도 1/3 실패'; echo '{\"ok\":true,\"balanceAfter\":4000,\"tickets\":[],\"errors\":[]}'"));
        cfg.setSidecarTimeoutSec(10);
        PythonLotterySidecarAdapter a = new PythonLotterySidecarAdapter(cfg, new ObjectMapper());

        SidecarResult r = a.purchaseWeekly(List.of(GameType.WIN720));

        assertThat(r.ok()).isTrue();
        assertThat(r.balanceAfter()).isEqualTo(4000);
    }
}
