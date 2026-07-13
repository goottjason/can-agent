package com.canagent.config;

import com.canagent.domain.lottery.GameType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "lottery")
public class LotteryConfig {

    private boolean enabled = false;         // 기능 전체 게이트(안전)
    private boolean dryRun = true;           // true면 구매 없이 로그인·잔액만
    private int balanceThreshold = 3000;     // 예치금 알림 임계(원)
    private List<String> sidecarCommand = new ArrayList<>();  // 예: [python3, /opt/canagent/sidecar/lottery/buy.py]
    private int sidecarTimeoutSec = 120;
    // 자동구매 대상 게임. 배포 시 서버 env(LOTTERY_GAMES=LOTTO645)로 로또만 제한 가능.
    // 연금(WIN720)은 사이드카 Playwright 미구현이라 실구매 활성 전까지 제외 권장.
    private List<GameType> games = new ArrayList<>(List.of(GameType.LOTTO645, GameType.WIN720));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<GameType> getGames() { return games; }
    public void setGames(List<GameType> games) { this.games = games; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public int getBalanceThreshold() { return balanceThreshold; }
    public void setBalanceThreshold(int balanceThreshold) { this.balanceThreshold = balanceThreshold; }
    public List<String> getSidecarCommand() { return sidecarCommand; }
    public void setSidecarCommand(List<String> sidecarCommand) { this.sidecarCommand = sidecarCommand; }
    public int getSidecarTimeoutSec() { return sidecarTimeoutSec; }
    public void setSidecarTimeoutSec(int sidecarTimeoutSec) { this.sidecarTimeoutSec = sidecarTimeoutSec; }
}
