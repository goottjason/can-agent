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
 * <p><b>PoC 미확정(B실사 §3)</b>: 토큰 발급 요청의 정확한 필드명(appKey/appSecret vs client_id/client_secret,
 * Basic 인증 여부)·content-type·응답 필드({@code access_token}/{@code expires_in})는 실호출 전까지 가정값이다.
 * 요청 본문 조립과 응답 파싱을 이 클래스 한 곳에 모아 두었으니, PoC로 확정되면 여기만 바꾼다.
 * (B실사 2차 힌트: 토큰 1시간 만료·50분 리프레시 권장 → 만료 5분 전 선제 갱신으로 여유 확보.)
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

        // === PoC 미확정 매핑(요청 본문): 실호출로 확정 대상 ===
        // 표준 OAuth2 client_credentials. 토스 실제 필드명이 client_id/client_secret일 수 있으므로
        // 확정 시 이 Map만 교체한다.
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appKey", props.getAppKey() == null ? "" : props.getAppKey(),
                "appSecret", props.getAppSecret() == null ? "" : props.getAppSecret()
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
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
