package com.canagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "api")
public class ApiConfig {

    private Dart dart = new Dart();
    private Krx krx = new Krx();

    public Dart getDart() { return dart; }
    public Krx getKrx() { return krx; }

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

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
