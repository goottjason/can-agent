package com.canagent.service.toss;

import com.canagent.config.TossProperties;
import com.canagent.config.TossTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 토스 일봉 캔들 REST 클라이언트.
 *
 * <p>계약(B실사 §2a): {@code GET /api/v1/candles}, interval=1d, 요청당 count≤200(default 100),
 * {@code before}(ISO8601 커서) 페이지네이션 + 응답 {@code nextBefore}. Bearer 토큰 헤더는
 * {@link TossTokenProvider}에서 요청 단위로만 부착(공유 RestTemplate 오염 방지, EdgarClient 선례).
 *
 * <p><b>=== PoC 확정(2026-07-11, §7) ===</b>: 응답은 {@code {"result":{"candles":[...],"nextBefore":...}}}로
 * 래핑된다. 이전엔 {@code candles}/{@code nextBefore}를 <b>루트</b>에서 찾아 실제 응답에선 빈 배열→시세동기화
 * 0건 <b>조용한 실패</b>였다(🔴 확정 버그). 이제 {@code result}를 벗긴 뒤 {@code candles}/{@code nextBefore}를 읽는다.
 * 쿼리 {@code symbol/interval/count/before[&adjusted=true]}, 캔들별
 * {@code timestamp(ISO8601 +09:00)/openPrice/highPrice/lowPrice/closePrice/volume/currency}. 매핑은 이 파일 한 곳에 집중.
 */
@Component
public class TossCandleClient {

    private static final Logger log = LoggerFactory.getLogger(TossCandleClient.class);

    /** 요청당 최대 캔들 수(B실사: max 200). */
    public static final int MAX_COUNT = 200;

    private static final String CANDLES_PATH = "/api/v1/candles";
    private static final String INTERVAL_DAILY = "1d";

    // === openapi.json 확정(2026-07-09): 쿼리 파라미터명 ===
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_INTERVAL = "interval";
    private static final String PARAM_COUNT = "count";
    private static final String PARAM_BEFORE = "before";

    // === PoC 확정(2026-07-11, §7): 응답 JSON 필드명 ===
    // 성공 응답은 최상위 result 래핑, 그 안에 candles/nextBefore. 캔들별: timestamp/openPrice/highPrice/lowPrice/closePrice/volume/currency.
    private static final String FIELD_RESULT = "result";
    private static final String FIELD_CANDLES = "candles";
    private static final String FIELD_NEXT_BEFORE = "nextBefore";
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_OPEN = "openPrice";
    private static final String FIELD_HIGH = "highPrice";
    private static final String FIELD_LOW = "lowPrice";
    private static final String FIELD_CLOSE = "closePrice";
    private static final String FIELD_VOLUME = "volume";

    private final RestTemplate restTemplate;
    private final TossProperties props;
    private final TossTokenProvider tokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TossCandleClient(RestTemplate restTemplate, TossProperties props, TossTokenProvider tokenProvider) {
        this.restTemplate = restTemplate;
        this.props = props;
        this.tokenProvider = tokenProvider;
    }

    /**
     * 일봉 캔들 한 페이지를 조회한다. 실패 시 빈 페이지(nextBefore=null)를 반환해 상위 루프가 종료되게 한다.
     *
     * @param ticker 주문·시세 심볼(예: AAPL)
     * @param count  요청 캔들 수(1..{@link #MAX_COUNT})
     * @param before 다음(과거) 페이지 커서. 첫 요청은 null(최신부터).
     */
    public TossCandlePage getDailyCandles(String ticker, int count, String before) {
        if (ticker == null || ticker.isBlank()) {
            log.warn("토스 캔들: ticker 없음 — skip");
            return TossCandlePage.empty();
        }
        int safeCount = Math.min(Math.max(1, count), MAX_COUNT);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(props.getBaseUrl() + CANDLES_PATH)
                .queryParam(PARAM_SYMBOL, ticker)
                .queryParam(PARAM_INTERVAL, INTERVAL_DAILY)
                .queryParam(PARAM_COUNT, safeCount);
        if (before != null && !before.isBlank()) {
            builder.queryParam(PARAM_BEFORE, before);
        }
        String url = builder.toUriString();

        String token = tokenProvider.getAccessToken();
        if (token == null) {
            log.warn("토스 캔들: 토큰 없음 — {} skip", ticker);
            return TossCandlePage.empty();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.set(HttpHeaders.ACCEPT, "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String body = resp.getBody();
            if (body == null || body.isBlank()) {
                log.warn("토스 캔들: 빈 응답 {} (before={})", ticker, before);
                return TossCandlePage.empty();
            }
            return parse(objectMapper.readTree(body), ticker);
        } catch (HttpClientErrorException e) {
            // 401: 토큰 만료/무효 → 무효화해 다음 호출에서 재발급
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("토스 캔들 401 {} — 토큰 무효화 후 다음 호출 재발급", ticker);
                tokenProvider.invalidateToken();
            } else if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("토스 캔들 429 rate-limit {} (before={})", ticker, before);
            } else {
                log.warn("토스 캔들 4xx {} {}: {}", ticker, e.getStatusCode(), e.getStatusText());
            }
            return TossCandlePage.empty();
        } catch (HttpServerErrorException e) {
            log.warn("토스 캔들 5xx {} {}", ticker, e.getStatusCode());
            return TossCandlePage.empty();
        } catch (Exception e) {
            log.warn("토스 캔들 호출 실패 {} (before={}): {}", ticker, before, e.getMessage());
            return TossCandlePage.empty();
        }
    }

    /**
     * 응답 JSON → TossCandlePage. 필드 부재/파싱 실패 캔들은 건너뛴다(견고).
     * === PoC 확정(2026-07-11, §7) ===: 성공 응답 {@code {"result":{"candles":[...],"nextBefore":...}}}에서
     * {@code result}를 벗긴 뒤 {@code candles}/{@code nextBefore}를 읽는다. result 부재 시 루트에서 폴백해도
     * 캔들 배열이 없으면 빈 페이지 → 상위 루프 종료(무한루프 방지).
     */
    private TossCandlePage parse(JsonNode root, String ticker) {
        JsonNode body = root.has(FIELD_RESULT) ? root.path(FIELD_RESULT) : root;
        JsonNode arr = body.path(FIELD_CANDLES);
        List<TossCandle> candles = new ArrayList<>();
        if (arr.isArray()) {
            for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) {
                TossCandle c = mapCandle(it.next(), ticker);
                if (c != null) candles.add(c);
            }
        }
        String nextBefore = body.path(FIELD_NEXT_BEFORE).asText(null);
        if (nextBefore != null && nextBefore.isBlank()) nextBefore = null;
        return new TossCandlePage(candles, nextBefore);
    }

    /**
     * 캔들 노드 1건 → {@link TossCandle}. 매핑의 유일 지점.
     * timestamp/OHLC 중 필수값이 없으면 null(해당 캔들 skip).
     * timestamp는 ISO8601 문자열 또는 epoch millis 숫자 양쪽을 처리(정확한 타입은 샌드박스 확정).
     */
    private TossCandle mapCandle(JsonNode node, String ticker) {
        try {
            LocalDate date = parseTimestamp(node.get(FIELD_TIMESTAMP));
            if (date == null) return null;

            BigDecimal open = decimal(node, FIELD_OPEN);
            BigDecimal high = decimal(node, FIELD_HIGH);
            BigDecimal low = decimal(node, FIELD_LOW);
            BigDecimal close = decimal(node, FIELD_CLOSE);
            if (open == null || high == null || low == null || close == null) {
                log.debug("토스 캔들 필드 결손 skip: {} {}", ticker, date);
                return null;
            }
            Long volume = longValue(node, FIELD_VOLUME);
            return new TossCandle(date, open, high, low, close, volume);
        } catch (Exception e) {
            log.debug("토스 캔들 매핑 실패 skip: {} - {}", ticker, e.getMessage());
            return null;
        }
    }

    /** timestamp 노드 → LocalDate. epoch millis(숫자) 또는 ISO8601 날짜/일시(문자열) 양쪽 지원. */
    private static LocalDate parseTimestamp(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) {
            return java.time.Instant.ofEpochMilli(node.asLong())
                    .atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        String s = node.asText(null);
        if (s == null || s.isBlank()) return null;
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return 0L;
        String s = v.asText(null);
        if (s == null || s.isBlank()) return 0L;
        try {
            return new BigDecimal(s.replace(",", "")).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
