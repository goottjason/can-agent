package com.canagent.service.edgar;

import com.canagent.config.EdgarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * SEC EDGAR companyfacts REST 클라이언트.
 *
 * <p>P4: CIK별 companyfacts JSON을 취득한다
 * ({@code https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json}).
 * <ul>
 *   <li><b>User-Agent 헤더 필수</b>(SEC 정책) — 요청마다 {@link EdgarProperties#getUserAgent()} 부착.
 *       공유 RestTemplate 빈에 인터셉터를 달아 DART/KRX 호출까지 오염시키지 않기 위해,
 *       헤더는 이 클라이언트에서 요청 단위로만 설정한다.</li>
 *   <li><b>10 req/s 준수</b> — 요청 간 최소 간격({@code minIntervalMs})을 강제 대기한다.</li>
 *   <li>4xx(404=CIK 없음/재무 미제출, 403=UA 누락)·5xx·기타 예외는 견고하게 삼키고 null 반환.</li>
 * </ul>
 *
 * <p>벌크 companyfacts.zip(~7GB) 나이틀리 수집은 후속 성능 최적화로 이월(P4b).
 */
@Component
public class EdgarClient {

    private static final Logger log = LoggerFactory.getLogger(EdgarClient.class);

    private final RestTemplate restTemplate;
    private final EdgarProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Object rateLock = new Object();
    private long lastRequestNanos = 0L;

    public EdgarClient(RestTemplate restTemplate, EdgarProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    /**
     * 주어진 CIK의 companyfacts JSON을 취득한다. 실패 시 null.
     *
     * @param cik SEC Central Index Key(패딩 유무 무관, 숫자만 추출·10자리 패딩)
     */
    public JsonNode getCompanyFacts(String cik) {
        String padded = normalizeCik(cik);
        if (padded == null) {
            log.warn("EDGAR: 유효하지 않은 CIK '{}'", cik);
            return null;
        }

        String url = props.getBaseUrl() + "/api/xbrl/companyfacts/CIK" + padded + ".json";

        throttle();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, props.getUserAgent());
            headers.set(HttpHeaders.ACCEPT, "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String body = resp.getBody();
            if (body == null || body.isBlank()) {
                log.warn("EDGAR: 빈 응답 CIK{}", padded);
                return null;
            }
            return objectMapper.readTree(body);
        } catch (HttpClientErrorException e) {
            // 404: 해당 CIK의 XBRL 재무 없음(ADR/외국기업 포함), 403: UA 문제 등
            log.warn("EDGAR 4xx CIK{}: {} — {}", padded, e.getStatusCode(), e.getStatusText());
            return null;
        } catch (HttpServerErrorException e) {
            log.warn("EDGAR 5xx CIK{}: {}", padded, e.getStatusCode());
            return null;
        } catch (Exception e) {
            log.warn("EDGAR 호출 실패 CIK{}: {}", padded, e.getMessage());
            return null;
        }
    }

    /**
     * CIK를 SEC URL 규격(10자리 zero-pad)으로 정규화한다. 숫자가 없으면 null.
     * 예: "320193" → "0000320193", "CIK0000320193" → "0000320193".
     */
    static String normalizeCik(String cik) {
        if (cik == null) return null;
        String digits = cik.trim().replaceFirst("(?i)^CIK", "").replaceAll("[^0-9]", "");
        digits = digits.replaceFirst("^0+", "");
        if (digits.isEmpty()) return null;
        if (digits.length() > 10) return null;
        return String.format("%010d", Long.parseLong(digits));
    }

    /** 직전 요청 이후 최소 간격이 지나지 않았으면 대기해 10 req/s를 준수한다. */
    private void throttle() {
        int minMs = props.getMinIntervalMs();
        if (minMs <= 0) return;
        synchronized (rateLock) {
            long now = System.nanoTime();
            if (lastRequestNanos != 0L) {
                long elapsedMs = (now - lastRequestNanos) / 1_000_000L;
                long wait = minMs - elapsedMs;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            lastRequestNanos = System.nanoTime();
        }
    }
}
