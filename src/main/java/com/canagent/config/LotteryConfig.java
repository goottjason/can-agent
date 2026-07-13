package com.canagent.config;

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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public int getBalanceThreshold() { return balanceThreshold; }
    public void setBalanceThreshold(int balanceThreshold) { this.balanceThreshold = balanceThreshold; }
    public List<String> getSidecarCommand() { return sidecarCommand; }
    public void setSidecarCommand(List<String> sidecarCommand) { this.sidecarCommand = sidecarCommand; }
    public int getSidecarTimeoutSec() { return sidecarTimeoutSec; }
    public void setSidecarTimeoutSec(int sidecarTimeoutSec) { this.sidecarTimeoutSec = sidecarTimeoutSec; }
}
