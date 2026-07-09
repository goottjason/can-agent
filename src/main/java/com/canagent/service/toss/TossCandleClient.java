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
 * <p><b>PoC 미확정(B실사 §2·§3)</b>: 아래 필드명 상수(캔들 배열키·OHLCV·nextBefore)와 쿼리 파라미터명
 * (symbol/interval/count/before)은 실호출 전 <b>가정값</b>이다. 매핑을 이 파일 한 곳(상수 + {@link #mapCandle})에
 * 모아 두었으니 P6 PoC 실호출로 확정되면 여기만 바꾼다. 어댑터 골격은 durable, 필드명은 tunable.
 */
@Component
public class TossCandleClient {

    private static final Logger log = LoggerFactory.getLogger(TossCandleClient.class);

    /** 요청당 최대 캔들 수(B실사: max 200). */
    public static final int MAX_COUNT = 200;

    private static final String CANDLES_PATH = "/api/v1/candles";
    private static final String INTERVAL_DAILY = "1d";

    // === PoC 미확정 가정: 쿼리 파라미터명 (실호출로 확정) ===
    private static final String PARAM_SYMBOL = "code";
    private static final String PARAM_INTERVAL = "interval";
    private static final String PARAM_COUNT = "count";
    private static final String PARAM_BEFORE = "before";

    // === PoC 미확정 가정: 응답 JSON 필드명 (실호출로 확정) ===
    private static final String FIELD_CANDLES = "candles";
    private static final String FIELD_NEXT_BEFORE = "nextBefore";
    private static final String FIELD_DATE = "date";
    private static final String FIELD_OPEN = "open";
    private static final String FIELD_HIGH = "high";
    private static final String FIELD_LOW = "low";
    private static final String FIELD_CLOSE = "close";
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

    /** 응답 JSON → TossCandlePage. 필드 부재/파싱 실패 캔들은 건너뛴다(견고). */
    private TossCandlePage parse(JsonNode root, String ticker) {
        JsonNode arr = root.path(FIELD_CANDLES);
        List<TossCandle> candles = new ArrayList<>();
        if (arr.isArray()) {
            for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) {
                TossCandle c = mapCandle(it.next(), ticker);
                if (c != null) candles.add(c);
            }
        }
        String nextBefore = root.path(FIELD_NEXT_BEFORE).asText(null);
        if (nextBefore != null && nextBefore.isBlank()) nextBefore = null;
        return new TossCandlePage(candles, nextBefore);
    }

    /**
     * 캔들 노드 1건 → {@link TossCandle}. <b>PoC 확정 대상 매핑의 유일 지점.</b>
     * date/OHLC 중 필수값이 없으면 null(해당 캔들 skip).
     */
    private TossCandle mapCandle(JsonNode node, String ticker) {
        try {
            String dateStr = node.path(FIELD_DATE).asText(null);
            if (dateStr == null || dateStr.isBlank()) return null;
            LocalDate date = LocalDate.parse(dateStr.length() > 10 ? dateStr.substring(0, 10) : dateStr);

            BigDecimal open = decimal(node, FIELD_OPEN);
            BigDecimal high = decimal(node, FIELD_HIGH);
            BigDecimal low = decimal(node, FIELD_LOW);
            BigDecimal close = decimal(node, FIELD_CLOSE);
            if (open == null || high == null || low == null || close == null) {
                log.debug("토스 캔들 필드 결손 skip: {} {}", ticker, dateStr);
                return null;
            }
            Long volume = longValue(node, FIELD_VOLUME);
            return new TossCandle(date, open, high, low, close, volume);
        } catch (Exception e) {
            log.debug("토스 캔들 매핑 실패 skip: {} - {}", ticker, e.getMessage());
            return null;
        }
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
