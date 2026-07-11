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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("토스 브로커 어댑터 (PoC 확정: result 언랩·잔고·소수매도·현재가·환율·체결)")
class TossBrokerAdapterTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private TossTokenProvider tokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** account를 env override로 지정 → /accounts 조회 없이 accountSeq 헤더 부착. */
    private TossProperties props() {
        TossProperties p = new TossProperties();
        p.setBaseUrl("https://toss.test");
        p.setAccount("1");
        return p;
    }

    private TossBrokerAdapter adapter() {
        return new TossBrokerAdapter(restTemplate, props(), tokenProvider);
    }

    private String fixture(String name) throws Exception {
        return new String(new ClassPathResource("toss/" + name).getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    // ========== 주문 생성 ==========

    @Test
    @DisplayName("소수 매수(Notional): MARKET+orderAmount+clientOrderId 본문, result.orderId 매핑")
    void placeBuy_notional_marketOrderBody() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("order-created.json")));

        OrderResult result = adapter().placeBuy("AAPL", OrderSpec.notional(new BigDecimal("100.00")));

        assertThat(result.success()).isTrue();
        assertThat(result.orderId()).isEqualTo("TOSS-ORD-1001"); // result 언랩 검증

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
        assertThat(body).doesNotContainKey("price"); // MARKET엔 price 금지
        assertThat((String) body.get("clientOrderId")).isNotBlank(); // 멱등성 키
        // Bearer + 계좌헤더(accountSeq)
        HttpHeaders headers = entity.getValue().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer TOK");
        assertThat(headers.getFirst("X-Tossinvest-Account")).isEqualTo("1");
    }

    @Test
    @DisplayName("정수 지정가 매도(Limit): LIMIT+quantity+price(2자리 반올림) 본문")
    void placeSell_integerLimit_body() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("order-created.json")));

        OrderResult result = adapter().placeSell("AAPL", OrderSpec.limit(new BigDecimal("2"), new BigDecimal("150.256")));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) entity.getValue().getBody();
        assertThat(body.get("side")).isEqualTo("SELL");
        assertThat(body.get("orderType")).isEqualTo("LIMIT");
        assertThat(body.get("quantity")).isEqualTo("2");
        assertThat(body.get("price")).isEqualTo("150.26"); // ≥$1 → 2자리 HALF_UP
    }

    @Test
    @DisplayName("소수 매도 교정(§8): 소수 수량 SELL은 MARKET+quantity, price 금지")
    void placeSell_fractional_isMarketNoPrice() throws Exception {
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
        assertThat(body.get("orderType")).isEqualTo("MARKET"); // 소수매도=MARKET
        assertThat(body.get("quantity")).isEqualTo("1.5");
        assertThat(body).doesNotContainKey("price"); // MARKET엔 price 금지
    }

    @Test
    @DisplayName("US 가격 정밀: <$1은 4자리, ≥$1은 2자리 반올림")
    void usPricePrecision() {
        assertThat(TossBrokerAdapter.roundUsPrice(new BigDecimal("0.12345")).toPlainString()).isEqualTo("0.1235");
        assertThat(TossBrokerAdapter.roundUsPrice(new BigDecimal("12.3456")).toPlainString()).isEqualTo("12.35");
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

    // ========== 주문 상세(execution 중첩) ==========

    @Test
    @DisplayName("주문 상세(§9): status + execution.{filledQuantity,averageFilledPrice,commission,tax} 매핑")
    void getOrder_mapsStatusAndExecution() throws Exception {
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
    @DisplayName("OrderStatus enum(§9): FILLED 매핑(우리 CLOSED 제거 회귀)")
    void getOrder_filledStatus() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"result\":{\"status\":\"FILLED\",\"execution\":{\"filledQuantity\":\"2\"}}}"));

        OrderStatus status = adapter().getOrder("TOSS-ORD-1001");
        assertThat(status.status()).isEqualTo(OrderStatus.Status.FILLED);
        assertThat(status.filledQty()).isEqualByComparingTo(new BigDecimal("2"));
    }

    // ========== 잔고(buying-power + holdings) ==========

    @Test
    @DisplayName("잔고(§3·§4): buying-power.cashBuyingPower + holdings.marketValue.amount.usd·items 매핑")
    void getBalance_mapsBuyingPowerAndHoldings() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        // buying-power → holdings 순서: URL로 응답 라우팅
        when(restTemplate.exchange(contains("/api/v1/buying-power"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("buying-power.json")));
        when(restTemplate.exchange(contains("/api/v1/holdings"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("holdings.json")));

        BrokerBalance balance = adapter().getBalance();

        assertThat(balance.success()).isTrue();
        assertThat(balance.availableCash()).isEqualByComparingTo(new BigDecimal("1234.56")); // cashBuyingPower(USD)
        assertThat(balance.totalEval()).isEqualByComparingTo(new BigDecimal("5678.90"));      // marketValue.amount.usd
        assertThat(balance.holdings()).hasSize(2);
        assertThat(balance.holdings().get(0).symbol()).isEqualTo("AAPL");
        assertThat(balance.holdings().get(0).quantity()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(balance.holdings().get(0).avgBuyPrice()).isEqualByComparingTo(new BigDecimal("150.00")); // averagePurchasePrice
        assertThat(balance.holdings().get(0).evalAmount()).isEqualByComparingTo(new BigDecimal("380.00"));  // item.marketValue.amount.usd
    }

    @Test
    @DisplayName("buying-power 요청에 currency=USD 파라미터·계좌헤더 부착")
    void getBalance_buyingPowerCarriesCurrencyAndAccount() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/buying-power"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("buying-power.json")));
        when(restTemplate.exchange(contains("/api/v1/holdings"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("holdings.json")));

        adapter().getBalance();

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        // 두 GET(buying-power+holdings)을 모두 캡처한 뒤 buying-power 호출을 골라 검증.
        verify(restTemplate, times(2)).exchange(url.capture(), eq(HttpMethod.GET), entity.capture(), eq(String.class));
        assertThat(url.getAllValues()).anyMatch(u -> u.contains("/api/v1/buying-power") && u.contains("currency=USD"));
        int idx = url.getAllValues().indexOf(url.getAllValues().stream()
                .filter(u -> u.contains("/api/v1/buying-power")).findFirst().orElseThrow());
        assertThat(entity.getAllValues().get(idx).getHeaders().getFirst("X-Tossinvest-Account")).isEqualTo("1");
    }

    @Test
    @DisplayName("잔고: buying-power + holdings 두 GET 호출을 합성한다")
    void getBalance_makesTwoCalls() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/buying-power"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("buying-power.json")));
        when(restTemplate.exchange(contains("/api/v1/holdings"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("holdings.json")));

        adapter().getBalance();

        verify(restTemplate).exchange(contains("/api/v1/buying-power"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(restTemplate).exchange(contains("/api/v1/holdings"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    // ========== 현재가·환율 ==========

    @Test
    @DisplayName("현재가(§5): /prices?symbols= → result[0].lastPrice (0 스텁 제거)")
    void getCurrentPrice_mapsLastPrice() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/prices"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("prices.json")));

        BigDecimal price = adapter().getCurrentPrice("AAPL");
        assertThat(price).isEqualByComparingTo(new BigDecimal("171.20"));

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(url.getValue()).contains("symbols=AAPL");
    }

    @Test
    @DisplayName("환율(§6): baseCurrency/quoteCurrency 파라미터 + result.rate 언랩")
    void getExchangeRate_paramsAndUnwrap() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("exchange-rate.json")));

        BigDecimal rate = adapter().getExchangeRate();
        assertThat(rate).isEqualByComparingTo(new BigDecimal("1506.90"));

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(url.getValue()).contains("baseCurrency=USD").contains("quoteCurrency=KRW");
    }

    // ========== accountSeq 조회(env override 없을 때) ==========

    @Test
    @DisplayName("계좌헤더(§2): TOSS_ACCOUNT 미설정이면 /accounts result[0].accountSeq 조회·캐시")
    void resolvesAccountSeqFromAccounts() throws Exception {
        TossProperties p = new TossProperties();
        p.setBaseUrl("https://toss.test"); // account 미설정
        TossBrokerAdapter noAcct = new TossBrokerAdapter(restTemplate, p, tokenProvider);

        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/accounts"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("accounts.json")));
        when(restTemplate.exchange(contains("/api/v1/orders"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fixture("order-created.json")));

        noAcct.placeBuy("AAPL", OrderSpec.notional(new BigDecimal("100")));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(contains("/api/v1/orders"), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        assertThat(entity.getValue().getHeaders().getFirst("X-Tossinvest-Account")).isEqualTo("1"); // accountSeq
    }

    // ========== 견고성 ==========

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
    @DisplayName("429 rate-limit은 예외를 삼키고 잔고 실패 결과 반환")
    void tooManyRequests_swallowedAsFailure() {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(contains("/api/v1/buying-power"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
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
    @DisplayName("result.orderId 없는 응답은 실패로 매핑(필드명 오타 조용실패 방지 회귀 포인트)")
    void missingOrderId_mapsFailure() {
        when(tokenProvider.getAccessToken()).thenReturn("TOK");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"result\":{\"message\":\"거부됨\"}}"));

        OrderResult result = adapter().placeBuy("AAPL", OrderSpec.notional(new BigDecimal("100")));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("거부됨");
    }
}
