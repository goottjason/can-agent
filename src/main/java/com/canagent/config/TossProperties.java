package com.canagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 토스증권 Open API 연동 설정.
 *
 * <p>P5(미국 대전환): 첫 토스 연동. 캔들(시세)·주문(P6) 공용 인증/엔드포인트 설정.
 * ApiConfig/EdgarProperties의 env 폴백 골격을 재사용하되, 설계 확정 최상위 키
 * ({@code toss.app-key}·{@code toss.app-secret}·{@code toss.base-url}·{@code toss.token-url})를
 * 그대로 노출하기 위해 별도 prefix로 둔다. 한투(api.korea-investment.*)/DART/KRX 키는 손대지 않는다(P9).
 *
 * <p><b>PoC 미확정(B실사 §3)</b>: OAuth2 토큰 엔드포인트의 정확한 경로·요청 형식과 캔들 API의 정확한
 * 필드명은 실호출 전까지 미확정이다. 그래서 토큰 URL은 절대경로 override({@code toss.token-url})가 가능하고,
 * 미지정 시 {@code base-url + tokenPath}로 조합한다. 실호출로 확정되면 값만 바꾸면 된다.
 */
@Configuration
@ConfigurationProperties(prefix = "toss")
public class TossProperties {

    /** OAuth2 client_credentials appKey(발급). 미설정 시 env {@code TOSS_APP_KEY} 폴백. */
    private String appKey;

    /** OAuth2 client_credentials appSecret(발급). 미설정 시 env {@code TOSS_APP_SECRET} 폴백. */
    private String appSecret;

    /** 토스 Open API base URL. B실사 1차출처 canonical 호스트. sandbox/live 전환은 env override. */
    private String baseUrl = "https://openapi.tossinvest.com";

    /**
     * 토큰 엔드포인트 절대 URL. 지정(또는 env {@code TOSS_TOKEN_URL})되면 그대로 사용,
     * 비어 있으면 {@code baseUrl + tokenPath}로 조합한다. PoC로 확정되면 여기만 바꾼다.
     */
    private String tokenUrl;

    /** 토큰 엔드포인트 상대 경로(baseUrl 하위). PoC 미확정 가정값 — 실호출로 확정. */
    private String tokenPath = "/api/v1/oauth/token";

    public String getAppKey() { return env("TOSS_APP_KEY", appKey); }

    public void setAppKey(String appKey) { this.appKey = appKey; }

    public String getAppSecret() { return env("TOSS_APP_SECRET", appSecret); }

    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

    public String getBaseUrl() { return env("TOSS_BASE_URL", baseUrl); }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }

    public String getTokenPath() { return tokenPath; }

    public void setTokenPath(String tokenPath) { this.tokenPath = tokenPath; }

    /** 최종 토큰 URL: 절대 override 우선, 없으면 baseUrl + tokenPath. */
    public String getTokenUrl() {
        String override = env("TOSS_TOKEN_URL", tokenUrl);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return getBaseUrl() + tokenPath;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) v = System.getProperty(name);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
