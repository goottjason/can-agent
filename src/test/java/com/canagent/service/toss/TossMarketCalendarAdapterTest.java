package com.canagent.service.toss;

import com.canagent.config.TossProperties;
import com.canagent.config.TossTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 토스 마켓 캘린더 어댑터 테스트.
 *
 * <p>#5(2026-07-11): 정적 휴일폴백 → 토스 US 캘린더 실연동. 실응답 픽스처
 * (거래일 today.regularMarket 존재 / 휴장일 regularMarket=null)로 매핑을 검증하고,
 * API 실패·토큰없음 시 기존 정적 NYSE 폴백으로 전환됨을 검증한다(회귀 안전).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("토스 마켓 캘린더 어댑터 (US 캘린더 실연동 + 정적 폴백)")
class TossMarketCalendarAdapterTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private TossTokenProvider tokenProvider;

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** account를 env override로 지정 → /accounts 조회 없이 accountSeq 헤더 부착. */
    private TossProperties props() {
        TossProperties p = new TossProperties();
        p.setBaseUrl("https://toss.test");
        p.setAccount("1");
        return p;
    }

    private TossMarketCalendarAdapter adapter() {
        return new TossMarketCalendarAdapter(restTemplate, props(), tokenProvider);
    }

    private String fixture(String name) throws Exception {
        return new String(new ClassPathResource("toss/" + name).getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    // ========== 토스 캘린더 실연동 ==========

    @Test
    @DisplayName("거래일: today.regularMarket 존재 → isTradingDay true, from/to·계좌헤더 부착")
    void tradingDay_fromCalendar() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/market-calendar/US"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("market-calendar-trading.json")));

        assertThat(adapter().isTradingDay(LocalDate.of(2026, 7, 10))).isTrue();
    }

    @Test
    @DisplayName("휴장일: today.regularMarket=null → isTradingDay false (캘린더 우선)")
    void holiday_fromCalendar_null() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/market-calendar/US"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("market-calendar-holiday.json")));

        // 07-11은 정적 폴백으로도 주말(토)이지만, 여기선 캘린더가 null을 주므로 false — 캘린더 우선 경로 검증.
        assertThat(adapter().isTradingDay(LocalDate.of(2026, 7, 11))).isFalse();
    }

    @Test
    @DisplayName("from=d&to=d 쿼리와 Bearer·계좌헤더(accountSeq)를 캘린더 호출에 부착")
    void tradingDay_carriesParamsAndHeaders() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        var url = org.mockito.ArgumentCaptor.forClass(String.class);
        var entity = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(url.capture(), eq(HttpMethod.GET), entity.capture(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("market-calendar-trading.json")));

        adapter().isTradingDay(LocalDate.of(2026, 7, 10));

        assertThat(url.getValue()).contains("/api/v1/market-calendar/US")
                .contains("from=2026-07-10").contains("to=2026-07-10");
        var headers = ((HttpEntity<?>) entity.getValue()).getHeaders();
        assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer TOK");
        assertThat(headers.getFirst("X-Tossinvest-Account")).isEqualTo("1");
    }

    // ========== isMarketOpen: regularMarket [start,end] Instant 범위 ==========

    @Test
    @DisplayName("개장: regularMarket [start,end] 내면 true — ET 10:00(2026-07-10)")
    void marketOpen_withinRegular() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/market-calendar/US"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("market-calendar-trading.json")));

        // 픽스처 regularMarket = KST 22:30~익일05:00 = ET 09:30~16:00(2026-07-10).
        assertThat(adapter().isMarketOpen(etInstant(2026, 7, 10, 10, 0))).isTrue();
    }

    @Test
    @DisplayName("경계: start(09:30)·end(16:00) 포함, 그 밖(09:00·16:30)은 폐장")
    void marketOpen_boundaries() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/market-calendar/US"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("market-calendar-trading.json")));

        TossMarketCalendarAdapter a = adapter();
        assertThat(a.isMarketOpen(etInstant(2026, 7, 10, 9, 30))).isTrue();   // start 포함
        assertThat(a.isMarketOpen(etInstant(2026, 7, 10, 16, 0))).isTrue();   // end 포함
        assertThat(a.isMarketOpen(etInstant(2026, 7, 10, 9, 0))).isFalse();   // 개장 전
        assertThat(a.isMarketOpen(etInstant(2026, 7, 10, 16, 30))).isFalse(); // 마감 후
    }

    @Test
    @DisplayName("휴장일: regularMarket=null이면 정규장 시간이어도 폐장")
    void marketOpen_holidayNull_closed() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/market-calendar/US"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("market-calendar-holiday.json")));

        assertThat(adapter().isMarketOpen(etInstant(2026, 7, 11, 10, 0))).isFalse();
    }

    // ========== 정적 폴백(회귀 안전) ==========

    @Test
    @DisplayName("토큰 없음 → 정적 NYSE 폴백: 평일 거래일·주말 비거래일")
    void noToken_staticFallback() {
        when(tokenProvider.getAccessToken()).thenReturn(null);

        TossMarketCalendarAdapter a = adapter();
        assertThat(a.isTradingDay(LocalDate.of(2026, 7, 10))).isTrue();  // 금(평일)
        assertThat(a.isTradingDay(LocalDate.of(2026, 7, 11))).isFalse(); // 토
        assertThat(a.isTradingDay(LocalDate.of(2026, 7, 12))).isFalse(); // 일
    }

    @Test
    @DisplayName("API 실패(5xx 아님, 임의 예외) → 정적 폴백으로 판정 계속(조용실패 아님)")
    void apiFailure_staticFallback() {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/market-calendar/US"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("connect timeout"));

        TossMarketCalendarAdapter a = adapter();
        assertThat(a.isTradingDay(LocalDate.of(2026, 7, 10))).isTrue();   // 정적 폴백: 평일
        assertThat(a.isMarketOpen(etInstant(2026, 7, 10, 10, 0))).isTrue(); // 정적 폴백: ET 정규장
    }

    @Test
    @DisplayName("API 401 → 토큰 무효화 + 정적 폴백")
    void apiUnauthorized_invalidatesAndFallsBack() {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/market-calendar/US"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        // 정적 폴백: 2025-07-04(독립기념일 금)은 휴장 → false.
        assertThat(adapter().isTradingDay(LocalDate.of(2025, 7, 4))).isFalse();
        org.mockito.Mockito.verify(tokenProvider).invalidateToken();
    }

    @Test
    @DisplayName("정적 폴백: 미국 휴일(고정일·요일기반·성금요일) 비거래일 — 회귀 유지")
    void staticFallback_usHolidays() {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        // 캘린더 호출을 모두 예외로 → 정적 폴백 경로.
        lenient().when(restTemplate.exchange(contains("/api/v1/market-calendar/US"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("down"));

        TossMarketCalendarAdapter a = adapter();
        assertThat(a.isTradingDay(LocalDate.of(2026, 1, 1))).isFalse();   // 신정
        assertThat(a.isTradingDay(LocalDate.of(2026, 6, 19))).isFalse();  // 준틴스
        assertThat(a.isTradingDay(LocalDate.of(2026, 12, 25))).isFalse(); // 성탄절
        assertThat(a.isTradingDay(LocalDate.of(2026, 1, 19))).isFalse();  // MLK
        assertThat(a.isTradingDay(LocalDate.of(2026, 11, 26))).isFalse(); // 추수감사절
        assertThat(a.isTradingDay(LocalDate.of(2026, 4, 3))).isFalse();   // 성금요일
    }

    private static Instant etInstant(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ET).toInstant();
    }
}
