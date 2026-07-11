package com.canagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 외부 API 설정. #6 잔재 정리(2026-07-11): 미국 대전환으로 데이터 소스가 SEC EDGAR·토스로 이동해
 * KRX/DART 중첩 프로퍼티(getKey → env DART_API_KEY/KRX_API_KEY)는 참조 0의 데드 코드가 되어 제거했다.
 * 재무는 {@code EdgarProperties}, 시세·주문은 {@code TossProperties}가 담당한다.
 * <b>한투(korea-investment) 프로퍼티는 폴백 broker(BrokerPort)로 유지한다</b>(토스 비활성 시 단독 사용).
 */
@Configuration
@ConfigurationProperties(prefix = "api")
public class ApiConfig {

    private KoreaInvestment koreaInvestment = new KoreaInvestment();

    public KoreaInvestment getKoreaInvestment() { return koreaInvestment; }

    public static class KoreaInvestment {
        private String appKey;
        private String appSecret;
        private String accountNumber;
        private boolean isReal = false;

        private String realBaseUrl = "https://openapi.koreainvestment.com:9443";
        private String mockBaseUrl = "https://openapivts.koreainvestment.com:29443";

        public String getAppKey() { return env("KOREA_INVESTMENT_APP_KEY", appKey); }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public String getAppSecret() { return env("KOREA_INVESTMENT_APP_SECRET", appSecret); }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getAccountNumber() { return env("KOREA_INVESTMENT_ACCOUNT_NUMBER", accountNumber); }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        public boolean isReal() {
            String v = System.getenv("KOREA_INVESTMENT_IS_REAL");
            if (v == null || v.isBlank()) v = System.getProperty("KOREA_INVESTMENT_IS_REAL");
            return v != null ? Boolean.parseBoolean(v) : isReal;
        }
        public void setReal(boolean real) { isReal = real; }
        public String getRealBaseUrl() { return realBaseUrl; }
        public void setRealBaseUrl(String realBaseUrl) { this.realBaseUrl = realBaseUrl; }
        public String getMockBaseUrl() { return mockBaseUrl; }
        public void setMockBaseUrl(String mockBaseUrl) { this.mockBaseUrl = mockBaseUrl; }

        public String getBaseUrl() {
            return isReal() ? realBaseUrl : mockBaseUrl;
        }

        public String getAccountCode() {
            String acct = getAccountNumber();
            if (acct == null || acct.isBlank()) return "";
            String[] parts = acct.split("-");
            return parts.length > 1 ? parts[1] : "01";
        }

        public String getAccountMain() {
            String acct = getAccountNumber();
            if (acct == null || acct.isBlank()) return "";
            String[] parts = acct.split("-");
            return parts[0];
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) v = System.getProperty(name);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
