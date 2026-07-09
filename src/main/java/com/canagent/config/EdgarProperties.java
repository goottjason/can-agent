package com.canagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SEC EDGAR 연동 설정.
 *
 * <p>P4(미국 대전환): 첫 실외부연동. SEC는 인증키가 없고 <b>User-Agent 헤더</b>(앱명+연락처)를
 * 요구하며 미설정 시 요청을 차단한다. 유량은 IP당 10 req/s이므로 {@code minIntervalMs}로
 * 요청 간 최소 간격을 강제한다.
 *
 * <p>ApiConfig의 env 폴백 골격을 재사용하되, 설계에서 확정한 최상위 키
 * ({@code edgar.user-agent}·{@code edgar.base-url})를 그대로 노출하기 위해 별도 prefix로 둔다.
 * 한투/DART(api.*) 키는 손대지 않는다.
 */
@Configuration
@ConfigurationProperties(prefix = "edgar")
public class EdgarProperties {

    /** SEC 요구 User-Agent(앱명 + 연락 이메일). 미설정 시 아래 기본값을 보낸다(운영에선 반드시 override 권장). */
    private String userAgent;

    private String baseUrl = "https://data.sec.gov";

    /**
     * 요청 간 최소 간격(ms). SEC 상한 10 req/s(=100ms) 대비 여유를 둔 125ms(≈8 req/s)가 기본.
     * 0 이하이면 스로틀 비활성(테스트용).
     */
    private int minIntervalMs = 125;

    public String getUserAgent() {
        String v = env("EDGAR_USER_AGENT", userAgent);
        return (v != null && !v.isBlank()) ? v : "CAN-Agent/1.0 (contact: admin@canagent.local)";
    }

    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getBaseUrl() { return env("EDGAR_BASE_URL", baseUrl); }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getMinIntervalMs() { return minIntervalMs; }

    public void setMinIntervalMs(int minIntervalMs) { this.minIntervalMs = minIntervalMs; }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) v = System.getProperty(name);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
