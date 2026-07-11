package com.canagent.service.toss;

import com.canagent.config.TossProperties;
import com.canagent.config.TossTokenProvider;
import com.canagent.port.MarketCalendarPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * 미국장 개장·휴장 판정 — {@link MarketCalendarPort} 유일 구현(E §5).
 *
 * <p><b>=== PoC 확정(2026-07-11, F §10) ===</b>: 토스 US market-calendar 실연동.
 * {@code GET /api/v1/market-calendar/US?from=d&to=d} + Bearer + 계좌헤더
 * {@code X-Tossinvest-Account: <accountSeq>}, 성공 응답 최상위 {@code {"result":...}} 래핑.
 * <ul>
 *   <li>{@link #isTradingDay(LocalDate)}: {@code from=d&to=d} 조회 → {@code result.today.regularMarket != null}면 거래일.
 *       (주말·미국 휴장일은 {@code regularMarket=null}.)
 *   <li>{@link #isMarketOpen(Instant)}: 오늘(ET 날짜) 조회 → {@code result.today.regularMarket}의
 *       [{@code startTime}, {@code endTime}](KST +09:00 오프셋 포함) Instant 범위에 now 포함 여부. null이면 false.
 * </ul>
 *
 * <p><b>폴백(durable)</b>: API 실패·토큰 없음·파싱 실패 시 기존 <b>정적 NYSE 휴일 폴백</b>(주말 + 연방·거래소 휴일 규칙)으로
 * 판정한다. 독립기념일 등에 주말만 보면 오주문이 나므로 정적 폴백은 필수 안전장치다(회귀 안전). 폴백은 규칙 계산이라
 * 연도 무관하게 성립한다. accountSeq는 {@code TossBrokerAdapter}와 동일하게 env override 우선, 없으면
 * {@code /accounts} result[0]에서 1회 조회·캐시한다.
 */
@Component
public class TossMarketCalendarAdapter implements MarketCalendarPort {

    private static final Logger log = LoggerFactory.getLogger(TossMarketCalendarAdapter.class);

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    // === PoC 확정(2026-07-11, F §10): 캘린더 엔드포인트·응답 필드 ===
    private static final String CALENDAR_PATH = "/api/v1/market-calendar/US";
    private static final String ACCOUNTS_PATH = "/api/v1/accounts";
    private static final String PARAM_FROM = "from";
    private static final String PARAM_TO = "to";
    private static final String FIELD_RESULT = "result";
    private static final String FIELD_TODAY = "today";
    private static final String FIELD_REGULAR_MARKET = "regularMarket";
    private static final String FIELD_START_TIME = "startTime";
    private static final String FIELD_END_TIME = "endTime";

    // === PoC 확정(2026-07-11, §2): 계좌 헤더·accounts 응답 필드 ===
    private static final String HEADER_ACCOUNT = "X-Tossinvest-Account";
    private static final String FIELD_ACCOUNT_SEQ = "accountSeq";

    private final RestTemplate restTemplate;
    private final TossProperties props;
    private final TossTokenProvider tokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** accountSeq 캐시(계좌콜 헤더). env override 우선, 없으면 /accounts에서 1회 조회 후 캐시. */
    private volatile String cachedAccountSeq;

    public TossMarketCalendarAdapter(RestTemplate restTemplate, TossProperties props, TossTokenProvider tokenProvider) {
        this.restTemplate = restTemplate;
        this.props = props;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public boolean isTradingDay(LocalDate date) {
        if (date == null) return false;
        JsonNode today = fetchToday(date);
        if (today != null) {
            // 토스 캘린더 우선: today.regularMarket 존재 → 거래일, null → 휴장(주말·휴일 포함).
            return !today.path(FIELD_REGULAR_MARKET).isMissingNode()
                    && !today.path(FIELD_REGULAR_MARKET).isNull();
        }
        // 폴백(durable): API 실패·토큰없음·파싱실패 시 정적 NYSE 휴일 규칙.
        return isTradingDayStatic(date);
    }

    @Override
    public boolean isMarketOpen(Instant now) {
        if (now == null) return false;
        ZonedDateTime et = now.atZone(ET);
        LocalDate today = et.toLocalDate();
        JsonNode todayNode = fetchToday(today);
        if (todayNode != null) {
            JsonNode regular = todayNode.path(FIELD_REGULAR_MARKET);
            if (regular.isMissingNode() || regular.isNull()) {
                return false; // 휴장일
            }
            Instant start = parseInstant(regular.path(FIELD_START_TIME));
            Instant end = parseInstant(regular.path(FIELD_END_TIME));
            if (start != null && end != null) {
                // [start, end] 포함(경계 포함). 토스 값은 KST(+09:00) 오프셋 포함 → Instant 비교로 존 무관.
                return !now.isBefore(start) && !now.isAfter(end);
            }
            // regularMarket은 있으나 시간 파싱 실패 → 정적 폴백(조용실패 방지).
        }
        // 폴백(durable): 정적 ET 정규장 판정.
        return isMarketOpenStatic(now);
    }

    // ========== 토스 캘린더 호출 ==========

    /**
     * {@code /market-calendar/US?from=d&to=d} 조회 → {@code result.today} 노드. 실패·토큰없음·파싱실패 시 null
     * (호출부가 정적 폴백으로 전환). result 언랩(P6b 패턴).
     */
    private JsonNode fetchToday(LocalDate date) {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            log.debug("토스 캘린더: 토큰 없음 — {} 정적 폴백", date);
            return null;
        }
        String iso = date.toString();
        String url = UriComponentsBuilder.fromHttpUrl(props.getBaseUrl() + CALENDAR_PATH)
                .queryParam(PARAM_FROM, iso)
                .queryParam(PARAM_TO, iso)
                .toUriString();
        try {
            HttpHeaders headers = bearerHeaders(token);
            headers.set(HEADER_ACCOUNT, resolveAccountSeq(token));
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            String body = resp.getBody();
            if (body == null || body.isBlank()) {
                log.debug("토스 캘린더: 빈 응답 {} — 정적 폴백", date);
                return null;
            }
            JsonNode result = unwrap(objectMapper.readTree(body));
            JsonNode today = result.path(FIELD_TODAY);
            if (today.isMissingNode() || today.isNull()) {
                log.debug("토스 캘린더: today 부재 {} — 정적 폴백", date);
                return null;
            }
            return today;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("토스 캘린더 401 {} — 토큰 무효화 후 정적 폴백", date);
                tokenProvider.invalidateToken();
            } else {
                log.warn("토스 캘린더 4xx {} {} — 정적 폴백", date, e.getStatusCode());
            }
            return null;
        } catch (Exception e) {
            log.warn("토스 캘린더 조회 실패 {}: {} — 정적 폴백", date, e.getMessage());
            return null;
        }
    }

    /**
     * 계좌콜 헤더용 accountSeq 확정(§2). TOSS_ACCOUNT env override 우선, 없으면
     * {@code /accounts} result[0].accountSeq 조회·캐시. 조회 실패 시 null(헤더 미부착 → 서버가 사유 반환).
     * TossBrokerAdapter와 동일 패턴.
     */
    private String resolveAccountSeq(String token) {
        String override = props.getAccount();
        if (override != null && !override.isBlank()) {
            return override;
        }
        String cached = cachedAccountSeq;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedAccountSeq != null) {
                return cachedAccountSeq;
            }
            try {
                String url = props.getBaseUrl() + ACCOUNTS_PATH;
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(token)), String.class);
                String body = resp.getBody();
                if (body != null && !body.isBlank()) {
                    JsonNode result = objectMapper.readTree(body).path(FIELD_RESULT);
                    if (result.isArray() && result.size() > 0) {
                        JsonNode seqNode = result.get(0).get(FIELD_ACCOUNT_SEQ);
                        if (seqNode != null && !seqNode.isNull()) {
                            String seq = seqNode.asText(null);
                            if (seq != null && !seq.isBlank()) {
                                cachedAccountSeq = seq;
                                log.info("토스 accountSeq 조회·캐시(캘린더): {}", seq);
                                return seq;
                            }
                        }
                    }
                }
                log.warn("토스 accounts(캘린더): accountSeq 없음");
            } catch (Exception e) {
                log.warn("토스 accounts(캘린더) 조회 실패: {}", e.getMessage());
            }
            return null;
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    /** === PoC 확정(2026-07-11): 성공 응답 최상위 {@code result} 래핑을 벗긴다. 부재 시 루트 폴백. === */
    private JsonNode unwrap(JsonNode root) {
        return root.has(FIELD_RESULT) ? root.path(FIELD_RESULT) : root;
    }

    /** ISO8601 오프셋 문자열(예: {@code 2026-07-10T22:30:00.000+09:00}) → Instant. 부재·파싱실패 시 null. */
    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String s = node.asText(null);
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 정적 NYSE 휴일 폴백(durable) ==========

    /** 정적 거래일 판정: 주말 제외 + 정적 NYSE 휴일 규칙. 토스 캘린더 미가용 구간 안전장치. */
    private boolean isTradingDayStatic(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !isExchangeHoliday(date);
    }

    /** 정적 개장 판정: ET 9:30~16:00 + 정적 거래일. */
    private boolean isMarketOpenStatic(Instant now) {
        ZonedDateTime et = now.atZone(ET);
        if (!isTradingDayStatic(et.toLocalDate())) {
            return false;
        }
        LocalTime t = et.toLocalTime();
        return !t.isBefore(MARKET_OPEN) && !t.isAfter(MARKET_CLOSE);
    }

    /**
     * NYSE/NASDAQ 정규 휴장일 판정(정적 규칙). 대상: 신정·MLK·워싱턴탄신일·성금요일·메모리얼데이·
     * 준틴스·독립기념일·노동절·추수감사절·성탄절. 고정일 휴일이 토·일이면 거래소 관측 규칙
     * (토→직전 금, 일→직후 월)로 대체 휴장한다. 단, 신정이 앞 연도로 넘어가는 12/31 대체는 두지 않는다.
     */
    private boolean isExchangeHoliday(LocalDate date) {
        // 고정일 휴일(관측 대체 포함)
        if (isObservedFixedHoliday(date, MonthDay.of(1, 1))) return true;    // 신정
        if (isObservedFixedHoliday(date, MonthDay.of(6, 19))) return true;   // 준틴스(2021~)
        if (isObservedFixedHoliday(date, MonthDay.of(7, 4))) return true;    // 독립기념일
        if (isObservedFixedHoliday(date, MonthDay.of(12, 25))) return true;  // 성탄절

        // 요일기반 휴일
        if (date.equals(nthWeekdayOfMonth(date.getYear(), 1, DayOfWeek.MONDAY, 3))) return true;   // MLK: 1월 셋째 월
        if (date.equals(nthWeekdayOfMonth(date.getYear(), 2, DayOfWeek.MONDAY, 3))) return true;   // 워싱턴탄신일: 2월 셋째 월
        if (date.equals(lastWeekdayOfMonth(date.getYear(), 5, DayOfWeek.MONDAY))) return true;     // 메모리얼데이: 5월 마지막 월
        if (date.equals(nthWeekdayOfMonth(date.getYear(), 9, DayOfWeek.MONDAY, 1))) return true;   // 노동절: 9월 첫 월
        if (date.equals(nthWeekdayOfMonth(date.getYear(), 11, DayOfWeek.THURSDAY, 4))) return true;// 추수감사절: 11월 넷째 목

        // 성금요일(부활절 이틀 전) — 거래소 휴장(연방 공휴일 아님)
        return date.equals(goodFriday(date.getYear()));
    }

    /** 고정일 휴일을 거래소 관측 규칙(토→직전 금, 일→직후 월)까지 반영해 해당 date가 휴장인지 판정. */
    private boolean isObservedFixedHoliday(LocalDate date, MonthDay holiday) {
        LocalDate actual = holiday.atYear(date.getYear());
        LocalDate observed = observed(actual);
        return date.equals(observed);
    }

    /** 고정일 휴일의 관측일: 토요일이면 직전 금요일, 일요일이면 다음 월요일, 평일이면 그대로. */
    private LocalDate observed(LocalDate actual) {
        DayOfWeek dow = actual.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) return actual.minusDays(1);
        if (dow == DayOfWeek.SUNDAY) return actual.plusDays(1);
        return actual;
    }

    private LocalDate nthWeekdayOfMonth(int year, int month, DayOfWeek dow, int n) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }

    private LocalDate lastWeekdayOfMonth(int year, int month, DayOfWeek dow) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.lastInMonth(dow));
    }

    /** 성금요일 = 부활절 - 2일. 부활절은 Anonymous Gregorian(Meeus/Jones/Butcher) 알고리즘으로 계산. */
    private LocalDate goodFriday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day).minusDays(2);
    }
}
