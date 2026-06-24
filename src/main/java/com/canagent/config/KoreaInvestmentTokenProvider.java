package com.canagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class KoreaInvestmentTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(KoreaInvestmentTokenProvider.class);
    private static final String TOKEN_PATH = "/oauth2/tokenP";

    private final RestTemplate restTemplate;
    private final ApiConfig apiConfig;

    private String accessToken;
    private LocalDateTime tokenExpiry;

    public KoreaInvestmentTokenProvider(RestTemplate restTemplate, ApiConfig apiConfig) {
        this.restTemplate = restTemplate;
        this.apiConfig = apiConfig;
    }

    public synchronized String getAccessToken() {
        if (accessToken != null && tokenExpiry != null && LocalDateTime.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        return refreshToken();
    }

    public synchronized String refreshToken() {
        ApiConfig.KoreaInvestment config = apiConfig.getKoreaInvestment();
        String url = config.getBaseUrl() + TOKEN_PATH;

        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", config.getAppKey(),
                "appsecret", config.getAppSecret()
        );

        try {
            var response = restTemplate.postForObject(url, body, Map.class);
            if (response != null && response.containsKey("access_token")) {
                accessToken = (String) response.get("access_token");
                int expiresIn = (int) response.getOrDefault("expires_in", 86400);
                tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn - 300);
                log.info("한국투자증권 토큰 발급 완료");
                return accessToken;
            }
            log.error("한국투자증권 토큰 발급 실패: {}", response);
        } catch (Exception e) {
            log.error("한국투자증권 토큰 발급 오류: {}", e.getMessage());
        }

        return null;
    }

    public void invalidateToken() {
        accessToken = null;
        tokenExpiry = null;
    }
}
