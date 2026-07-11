package com.canagent.service.toss;

import com.canagent.config.TossProperties;
import com.canagent.config.TossTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("토스 캔들 클라이언트 (JSON 파싱·헤더·페이지네이션·오류)")
class TossCandleClientTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private TossTokenProvider tokenProvider;

    private TossProperties props() {
        TossProperties p = new TossProperties();
        p.setBaseUrl("https://toss.test");
        return p;
    }

    private String fixture(String name) throws Exception {
        return new String(new ClassPathResource("toss/" + name).getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("캔들 JSON을 OHLCV+날짜로 매핑하고 nextBefore를 읽는다")
    void parsesCandlesAndCursor() throws Exception {
        TossCandleClient client = new TossCandleClient(restTemplate, props(), tokenProvider);
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("candles-page1.json")));

        TossCandlePage page = client.getDailyCandles("AAPL", 200, null);

        assertThat(page.getCandles()).hasSize(3);
        TossCandle first = page.getCandles().get(0);
        assertThat(first.getDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(first.getOpen()).isEqualByComparingTo(new BigDecimal("170.00"));
        assertThat(first.getHigh()).isEqualByComparingTo(new BigDecimal("172.50"));
        assertThat(first.getLow()).isEqualByComparingTo(new BigDecimal("169.80"));
        assertThat(first.getClose()).isEqualByComparingTo(new BigDecimal("171.20"));
        assertThat(first.getVolume()).isEqualTo(52000000L);
        assertThat(page.hasMore()).isTrue();
        // === PoC 확정(2026-07-11, §7): result.nextBefore 언랩(루트 아님). timestamp는 ISO8601 +09:00. ===
        assertThat(page.getNextBefore()).isEqualTo("2024-03-13T00:00:00+09:00");
    }

    @Test
    @DisplayName("result 언랩 회귀: 루트 파싱이면 시세 0건(조용실패) — result.candles를 읽어야 3건")
    void unwrapsResultEnvelope() throws Exception {
        TossCandleClient client = new TossCandleClient(restTemplate, props(), tokenProvider);
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("candles-page1.json")));

        TossCandlePage page = client.getDailyCandles("AAPL", 200, null);

        // 실 응답은 {"result":{"candles":[...]}} — 루트에서 candles를 찾으면 0건이 되어 시세동기화가 조용히 멈춘다.
        assertThat(page.getCandles()).isNotEmpty();
        assertThat(page.getNextBefore()).isNotNull();
    }

    @Test
    @DisplayName("Bearer 토큰 헤더와 symbol/interval/count 쿼리를 부착한다")
    void attachesTokenAndQuery() throws Exception {
        TossCandleClient client = new TossCandleClient(restTemplate, props(), tokenProvider);
        when(tokenProvider.getAccessToken()).thenReturn("TOK-XYZ");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("candles-page1.json")));

        client.getDailyCandles("AAPL", 200, null);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.GET), entity.capture(), eq(String.class));

        assertThat(url.getValue())
                .contains("/api/v1/candles")
                .contains("symbol=AAPL")
                .contains("interval=1d")
                .contains("count=200");
        HttpHeaders headers = entity.getValue().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer TOK-XYZ");
    }

    @Test
    @DisplayName("before 커서가 있으면 쿼리에 포함한다(페이지네이션)")
    void includesBeforeCursor() throws Exception {
        TossCandleClient client = new TossCandleClient(restTemplate, props(), tokenProvider);
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("candles-page2.json")));

        client.getDailyCandles("AAPL", 200, "2024-03-13T00:00:00Z");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(url.getValue()).contains("before=2024-03-13");
    }

    @Test
    @DisplayName("nextBefore=null 페이지는 hasMore=false")
    void lastPageNoMore() throws Exception {
        TossCandleClient client = new TossCandleClient(restTemplate, props(), tokenProvider);
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("candles-page2.json")));

        TossCandlePage page = client.getDailyCandles("AAPL", 200, "cursor");
        assertThat(page.getCandles()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("4xx는 예외를 삼키고 빈 페이지 반환")
    void swallows4xx() {
        TossCandleClient client = new TossCandleClient(restTemplate, props(), tokenProvider);
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        TossCandlePage page = client.getDailyCandles("AAPL", 200, null);
        assertThat(page.getCandles()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("401은 토큰을 무효화하고 빈 페이지 반환")
    void unauthorizedInvalidatesToken() {
        TossCandleClient client = new TossCandleClient(restTemplate, props(), tokenProvider);
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        TossCandlePage page = client.getDailyCandles("AAPL", 200, null);
        assertThat(page.getCandles()).isEmpty();
        verify(tokenProvider).invalidateToken();
    }

    @Test
    @DisplayName("토큰이 null이면 호출 없이 빈 페이지")
    void noTokenNoCall() {
        TossCandleClient client = new TossCandleClient(restTemplate, props(), tokenProvider);
        when(tokenProvider.getAccessToken()).thenReturn(null);

        TossCandlePage page = client.getDailyCandles("AAPL", 200, null);
        assertThat(page.getCandles()).isEmpty();
    }
}
