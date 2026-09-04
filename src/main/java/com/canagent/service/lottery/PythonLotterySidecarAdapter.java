package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.SidecarError;
import com.canagent.port.dto.SidecarResult;
import com.canagent.port.dto.SidecarResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    @Override
    public SidecarResults checkResults() {
        List<String> cmd = new ArrayList<>(config.getSidecarCommand());
        cmd.add("result");
        try {
            String out = runProcess(cmd);
            return parseResultsJson(out);
        } catch (Exception e) {
            log.error("사이드카 결과조회 실행 실패: {}", e.getMessage());
            return new SidecarResults(false, List.of(),
                    List.of(new SidecarError("ALL", "사이드카 실행 실패: " + e.getMessage())));
        }
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
        // 명령 미설정 시 ProcessBuilder의 모호한 IOException 대신 명확한 메시지로 조기 실패
        if (config.getSidecarCommand().isEmpty()
                || config.getSidecarCommand().get(0).isBlank()) {
            throw new IllegalStateException(
                "lottery.sidecar-command 미설정 — YAML 배열로 설정 필요, 예: [\"python3\", \"/opt/canagent/sidecar/lottery/buy.py\"]");
        }
        log.info("복권 사이드카 실행: {}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);  // stderr를 stdout에 병합 — 파이프 버퍼 데드락 방지
        Process process = pb.start();
        // 출력은 별도 스레드로 읽는다. 같은 스레드에서 readAllBytes()를 하면 사이드카가 멎었을 때
        // waitFor 타임아웃에 도달하지 못해 스케줄러 스레드(pool=3)가 영구 점유된다.
        StringBuffer buf = new StringBuffer();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) buf.append(line).append('\n');
            } catch (Exception ignored) {
                // 프로세스 강제 종료 시 스트림이 끊기는 것은 정상 — 타임아웃 경로에서 처리한다.
            }
        }, "lottery-sidecar-out");
        reader.setDaemon(true);
        reader.start();

        boolean done = process.waitFor(config.getSidecarTimeoutSec(), TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            reader.join(2000);
            log.error("사이드카 타임아웃 — 부분 출력: {}", buf.toString().strip());
            throw new IllegalStateException("사이드카 타임아웃(" + config.getSidecarTimeoutSec() + "s)");
        }
        reader.join(5000);   // 종료 후 남은 출력 수거
        String stdout = buf.toString();
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

    /** stdout JSON → SidecarResults. package-private(테스트 대상). */
    SidecarResults parseResultsJson(String json) {
        try {
            return mapper.readValue(json, SidecarResults.class);
        } catch (Exception e) {
            log.error("사이드카 결과 JSON 파싱 실패: {} / raw={}", e.getMessage(), json);
            return new SidecarResults(false, List.of(),
                    List.of(new SidecarError("ALL", "JSON 파싱 실패")));
        }
    }
}
