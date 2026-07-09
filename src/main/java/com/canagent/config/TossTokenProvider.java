package com.canagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 토스증권 OAuth2 client_credentials 토큰 캐시 제공자.
 *
 * <p>P5: 첫 토스 인증. 캔들(P5)·주문(P6) 어댑터가 공용으로 이 빈에서 Bearer 토큰을 얻는다.
 * {@code KoreaInvestmentTokenProvider}의 캐시 패턴(만료 전 갱신·{@code synchronized}·invalidate)을 이식했다.
 *
 * <p><b>openapi.json 확정(2026-07-09)</b>: {@code POST /oauth2/token}, application/x-www-form-urlencoded,
 * {@code grant_type=client_credentials} + {@code client_id} + {@code client_secret}. 응답
 * {@code access_token}/{@code token_type}/{@code expires_in}. 요청/응답 조립을 이 클래스 한 곳에 모았다.
 * (만료 5분 전 선제 갱신.)
 */
@Component
public class TossTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TossTokenProvider.class);

    /** 만료 몇 초 전에 선제 갱신할지(캐시된 토큰 조기 무효화 버퍼). */
    private static final int REFRESH_BUFFER_SECONDS = 300;
    /** 응답에 expires_in이 없을 때의 보수적 기본값(초). B실사 힌트 1시간 만료 가정. */
    private static final int DEFAULT_EXPIRES_IN = 3600;

    private final RestTemplate restTemplate;
    private final TossProperties props;

    private String accessToken;
    private LocalDateTime tokenExpiry;

    public TossTokenProvider(RestTemplate restTemplate, TossProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    /** 캐시된 유효 토큰이 있으면 그대로, 아니면 갱신. */
    public synchronized String getAccessToken() {
        if (accessToken != null && tokenExpiry != null && LocalDateTime.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        return refreshToken();
    }

    public synchronized String refreshToken() {
        String url = props.getTokenUrl();

        // === openapi.json 확정(2026-07-09): POST /oauth2/token, application/x-www-form-urlencoded,
        // grant_type=client_credentials + client_id + client_secret. ===
        org.springframework.util.MultiValueMap<String, String> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.getAppKey() == null ? "" : props.getAppKey());
        form.add("client_secret", props.getAppSecret() == null ? "" : props.getAppSecret());

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, String>> request =
                new org.springframework.http.HttpEntity<>(form, headers);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            // === PoC 미확정 매핑(응답 필드): access_token / expires_in 가정 ===
            if (response != null && response.containsKey("access_token")) {
                accessToken = String.valueOf(response.get("access_token"));
                int expiresIn = toInt(response.get("expires_in"), DEFAULT_EXPIRES_IN);
                tokenExpiry = LocalDateTime.now().plusSeconds(Math.max(0, expiresIn - REFRESH_BUFFER_SECONDS));
                log.info("토스 토큰 발급 완료 (만료 {}초, {}초 전 선제갱신)", expiresIn, REFRESH_BUFFER_SECONDS);
                return accessToken;
            }
            log.error("토스 토큰 발급 실패(access_token 없음): {}", response);
        } catch (Exception e) {
            log.error("토스 토큰 발급 오류: {}", e.getMessage());
        }
        return null;
    }

    public synchronized void invalidateToken() {
        accessToken = null;
        tokenExpiry = null;
    }

    private static int toInt(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
