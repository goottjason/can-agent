package com.canagent.service.toss;

import com.canagent.config.TossProperties;
import com.canagent.config.TossTokenProvider;
import com.canagent.port.dto.BrokerBalance;
import com.canagent.port.dto.OrderResult;
import com.canagent.port.dto.OrderSpec;
import com.canagent.port.dto.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("토스 브로커 어댑터 (주문·잔고·환율 매핑·견고성)")
class TossBrokerAdapterTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private TossTokenProvider tokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TossProperties props() {
        TossProperties p = new TossProperties();
        p.setBaseUrl("https://toss.test");
        p.setAccount("ACC-1");
        return p;
    }

    private TossBrokerAdapter adapter() {
        return new TossBrokerAdapter(restTemplate, props(), tokenProvider);
    }

    private String fixture(String name) throws Exception {
        return new String(new ClassPathResource("toss/" + name).getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("소수 매수(Notional): orderType=MARKET·orderAmount 본문 + orderId 매핑")
    void placeBuy_notional_marketOrderBody() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("order-created.json")));

        OrderResult result = adapter().placeBuy("AAPL", OrderSpec.notional(new BigDecimal("100.00")));

        assertThat(result.success()).isTrue();
        assertThat(result.orderId()).isEqualTo("TOSS-ORD-1001");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.POST), entity.capture(), eq(String.class));

        assertThat(url.getValue()).endsWith("/api/v1/orders");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) entity.getValue().getBody();
        assertThat(body.get("symbol")).isEqualTo("AAPL");
        assertThat(body.get("side")).isEqualTo("BUY");
        assertThat(body.get("orderType")).isEqualTo("MARKET");
        assertThat(body.get("orderAmount")).isEqualTo("100.00");
        assertThat(body).doesNotContainKey("quantity");
        // Bearer + 계좌 헤더 부착
        HttpHeaders headers = entity.getValue().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer TOK");
        assertThat(headers.getFirst("X-Tossinvest-Account")).isEqualTo("ACC-1");
    }

    @Test
    @DisplayName("지정가 매도(Limit): orderType=LIMIT·quantity·price 본문")
    void placeSell_limit_body() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("order-created.json")));

        OrderResult result = adapter().placeSell("AAPL", OrderSpec.limit(new BigDecimal("1.5"), new BigDecimal("150.25")));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) entity.getValue().getBody();
        assertThat(body.get("side")).isEqualTo("SELL");
        assertThat(body.get("orderType")).isEqualTo("LIMIT");
        assertThat(body.get("quantity")).isEqualTo("1.5");
        assertThat(body.get("price")).isEqualTo("150.25");
    }

    @Test
    @DisplayName("취소: POST /orders/{id}/cancel")
    void cancel_hitsCancelEndpoint() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("order-created.json")));

        adapter().cancel("TOSS-ORD-1001");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        assertThat(url.getValue()).endsWith("/api/v1/orders/TOSS-ORD-1001/cancel");
    }

    @Test
    @DisplayName("정정: POST /orders/{id}/modify (지정가만)")
    void modify_limitOnly() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("order-created.json")));

        OrderResult result = adapter().modify("TOSS-ORD-1001", OrderSpec.limit(new BigDecimal("2"), new BigDecimal("151.00")));
        assertThat(result.success()).isTrue();

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        assertThat(url.getValue()).endsWith("/api/v1/orders/TOSS-ORD-1001/modify");
    }

    @Test
    @DisplayName("정정에 Notional을 주면 사유 담아 실패(지정가만 가능)")
    void modify_notional_fails() {
        OrderResult result = adapter().modify("X", OrderSpec.notional(new BigDecimal("100")));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("지정가");
    }

    @Test
    @DisplayName("주문 상세: status/체결수량/평균체결가/수수료/세금 매핑")
    void getOrder_mapsStatusAndFills() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("order-detail.json")));

        OrderStatus status = adapter().getOrder("TOSS-ORD-1001");

        assertThat(status.status()).isEqualTo(OrderStatus.Status.PARTIAL_FILLED);
        assertThat(status.filledQty()).isEqualByComparingTo(new BigDecimal("1.2345"));
        assertThat(status.avgFillPrice()).isEqualByComparingTo(new BigDecimal("150.25"));
        assertThat(status.commission()).isEqualByComparingTo(new BigDecimal("0.35"));
        assertThat(status.tax()).isEqualByComparingTo(new BigDecimal("0.10"));
    }

    @Test
    @DisplayName("잔고(assets): availableCash·평가·보유목록 매핑")
    void getBalance_mapsAssets() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("assets.json")));

        BrokerBalance balance = adapter().getBalance();

        assertThat(balance.success()).isTrue();
        assertThat(balance.availableCash()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(balance.totalEval()).isEqualByComparingTo(new BigDecimal("5678.90"));
        assertThat(balance.holdings()).hasSize(2);
        assertThat(balance.holdings().get(0).symbol()).isEqualTo("AAPL");
        assertThat(balance.holdings().get(0).quantity()).isEqualByComparingTo(new BigDecimal("2.5"));
    }

    @Test
    @DisplayName("환율: USD/KRW rate 매핑")
    void getExchangeRate_mapsRate() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("exchange-rate.json")));

        BigDecimal rate = adapter().getExchangeRate();
        assertThat(rate).isEqualByComparingTo(new BigDecimal("1385.50"));
    }

    @Test
    @DisplayName("401은 토큰 무효화하고 실패 결과 반환(조용실패 아님 — 사유 담김)")
    void unauthorized_invalidatesTokenAndFails() {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        OrderResult result = adapter().placeBuy("AAPL", OrderSpec.notional(new BigDecimal("100")));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isNotBlank();
        verify(tokenProvider).invalidateToken();
    }

    @Test
    @DisplayName("429 rate-limit은 예외를 삼키고 실패 결과 반환")
    void tooManyRequests_swallowedAsFailure() {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many", null, null, null));

        BrokerBalance balance = adapter().getBalance();
        assertThat(balance.success()).isFalse();
    }

    @Test
    @DisplayName("토큰이 null이면 호출 없이 실패 결과")
    void noToken_noCallFails() {
        when(tokenProvider.getAccessToken()).thenReturn(null);

        OrderResult result = adapter().placeBuy("AAPL", OrderSpec.notional(new BigDecimal("100")));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("토큰");
    }

    @Test
    @DisplayName("orderId 없는 응답은 실패로 매핑(필드명 오타 조용실패 방지 회귀 포인트)")
    void missingOrderId_mapsFailure() {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"message\":\"거부됨\"}"));

        OrderResult result = adapter().placeBuy("AAPL", OrderSpec.notional(new BigDecimal("100")));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("거부됨");
    }
}
