package com.canagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "api")
public class ApiConfig {

    private Dart dart = new Dart();
    private Krx krx = new Krx();
    private KoreaInvestment koreaInvestment = new KoreaInvestment();

    public Dart getDart() { return dart; }
    public Krx getKrx() { return krx; }
    public KoreaInvestment getKoreaInvestment() { return koreaInvestment; }

    public static class Dart {
        private String key;
        private String baseUrl = "https://opendart.fss.or.kr/api";

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class Krx {
        private String key;
        private String baseUrl = "https://apis.data.go.kr/1160100/service/getStockPriceInfo";
        private String stockListUrl = "https://apis.data.go.kr/1160100/service/GetKrxListedInfoService/getItemInfo";

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getStockListUrl() { return stockListUrl; }
        public void setStockListUrl(String stockListUrl) { this.stockListUrl = stockListUrl; }
    }

    public static class KoreaInvestment {
        private String appKey;
        private String appSecret;
        private String accountNumber;
        private boolean isReal = false;

        private String realBaseUrl = "https://openapi.koreainvestment.com:9443";
        private String mockBaseUrl = "https://openapivts.koreainvestment.com:29443";

        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        public boolean isReal() { return isReal; }
        public void setReal(boolean real) { isReal = real; }
        public String getRealBaseUrl() { return realBaseUrl; }
        public void setRealBaseUrl(String realBaseUrl) { this.realBaseUrl = realBaseUrl; }
        public String getMockBaseUrl() { return mockBaseUrl; }
        public void setMockBaseUrl(String mockBaseUrl) { this.mockBaseUrl = mockBaseUrl; }

        public String getBaseUrl() {
            return isReal ? realBaseUrl : mockBaseUrl;
        }

        public String getAccountCode() {
            if (accountNumber == null || accountNumber.isBlank()) {
                return "";
            }
            String[] parts = accountNumber.split("-");
            return parts.length > 1 ? parts[1] : "01";
        }

        public String getAccountMain() {
            if (accountNumber == null || accountNumber.isBlank()) {
                return "";
            }
            String[] parts = accountNumber.split("-");
            return parts[0];
        }
    }
}
