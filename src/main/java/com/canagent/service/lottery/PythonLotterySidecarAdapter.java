package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.SidecarError;
import com.canagent.port.dto.SidecarResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PythonLotterySidecarAdapter implements LotterySidecarPort {

    private static final Logger log = LoggerFactory.getLogger(PythonLotterySidecarAdapter.class);

    private final LotteryConfig config;
    private final ObjectMapper mapper;

    public PythonLotterySidecarAdapter(LotteryConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public SidecarResult purchaseWeekly(List<GameType> games) {
        List<String> cmd = new ArrayList<>(config.getSidecarCommand());
        cmd.add("buy");
        cmd.add("--games");
        cmd.add(games.stream().map(Enum::name).collect(Collectors.joining(",")));
        if (config.isDryRun()) cmd.add("--dry-run");
        return runAndParse(cmd);
    }

    @Override
    public int getBalance() {
        List<String> cmd = new ArrayList<>(config.getSidecarCommand());
        cmd.add("balance");
        return runAndParse(cmd).balanceAfter();
    }

    private SidecarResult runAndParse(List<String> cmd) {
        try {
            String out = runProcess(cmd);
            return parseSidecarJson(out);
        } catch (Exception e) {
            log.error("사이드카 실행 실패: {}", e.getMessage());
            return new SidecarResult(false, 0, List.of(),
                    List.of(new SidecarError("ALL", "사이드카 실행 실패: " + e.getMessage())));
        }
    }

    private String runProcess(List<String> cmd) throws Exception {
        log.info("복권 사이드카 실행: {}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        String stdout = new String(process.getInputStream().readAllBytes());
        boolean done = process.waitFor(config.getSidecarTimeoutSec(), TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new IllegalStateException("사이드카 타임아웃(" + config.getSidecarTimeoutSec() + "s)");
        }
        // 사이드카는 JSON을 마지막 줄에 출력한다(로그와 분리).
        String[] lines = stdout.strip().split("\\R");
        return lines[lines.length - 1];
    }

    /** stdout JSON → SidecarResult. package-private(테스트 대상). */
    SidecarResult parseSidecarJson(String json) {
        try {
            return mapper.readValue(json, SidecarResult.class);
        } catch (Exception e) {
            log.error("사이드카 JSON 파싱 실패: {} / raw={}", e.getMessage(), json);
            return new SidecarResult(false, 0, List.of(),
                    List.of(new SidecarError("ALL", "JSON 파싱 실패")));
        }
    }
}
