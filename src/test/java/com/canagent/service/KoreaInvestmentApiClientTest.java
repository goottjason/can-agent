package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.config.KoreaInvestmentTokenProvider;
import com.canagent.port.dto.BrokerBalance;
import com.canagent.port.dto.OrderResult;
import com.canagent.port.dto.OrderSpec;
import com.canagent.service.dto.KoreaInvestmentOrderResponse;
import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.service.dto.KoreaInvestmentPriceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("한국투자증권 브로커 어댑터 단위테스트 (broker-중립 포트)")
class KoreaInvestmentApiClientTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private ApiConfig apiConfig;
    @Mock
    private KoreaInvestmentTokenProvider tokenProvider;

    @InjectMocks
    private KoreaInvestmentApiClient apiClient;

    private ApiConfig.KoreaInvestment koreaInvestmentConfig;

    @BeforeEach
    void setUp() {
        koreaInvestmentConfig = new ApiConfig.KoreaInvestment();
        koreaInvestmentConfig.setAppKey("test-app-key");
        koreaInvestmentConfig.setAppSecret("test-app-secret");
        koreaInvestmentConfig.setAccountNumber("12345678-01");
        koreaInvestmentConfig.setReal(false);
        lenient().when(apiConfig.getKoreaInvestment()).thenReturn(koreaInvestmentConfig);
        lenient().when(tokenProvider.getAccessToken()).thenReturn("test-access-token");
    }

    private void stubOrderResponse(String rtCd, String orderNo) {
        KoreaInvestmentOrderResponse response = new KoreaInvestmentOrderResponse();
        response.setRtCd(rtCd);
        response.setMsg1("주문 응답");
        if (orderNo != null) {
            KoreaInvestmentOrderResponse.OrderOutput output = new KoreaInvestmentOrderResponse.OrderOutput();
            output.setOrderNo(orderNo);
            response.setOutput(output);
        }
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(response));
    }

    @Test
    @DisplayName("placeBuy(Limit) 성공 → OrderResult 접수(주문번호 매핑)")
    void placeBuy_limit_success() {
        stubOrderResponse("0", "0012345678");

        OrderResult result = apiClient.placeBuy("005930", OrderSpec.limit(new BigDecimal("10"), new BigDecimal("73000")));

        assertThat(result.success()).isTrue();
        assertThat(result.orderId()).isEqualTo("0012345678");
    }

    @Test
    @DisplayName("placeSell(Limit) 성공 → OrderResult 접수")
    void placeSell_limit_success() {
        stubOrderResponse("0", "0012345679");

        OrderResult result = apiClient.placeSell("005930", OrderSpec.limit(new BigDecimal("10"), new BigDecimal("75000")));

        assertThat(result.success()).isTrue();
        assertThat(result.orderId()).isEqualTo("0012345679");
    }

    @Test
    @DisplayName("getBalance → BrokerBalance(주문 가능 현금 매핑)")
    void getBalance_success_mapsAvailableCash() {
        KoreaInvestmentBalanceResponse response = new KoreaInvestmentBalanceResponse();
        response.setRtCd("0");
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            KoreaInvestmentBalanceResponse.AccountSummary summary = mapper.readValue(
                    "{\"prvs_rcdl_excc_amt\":\"1000000\",\"tot_evlu_amt\":\"1500000\"}",
                    KoreaInvestmentBalanceResponse.AccountSummary.class);
            response.setOutput2(java.util.List.of(summary));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentBalanceResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        BrokerBalance balance = apiClient.getBalance();

        assertThat(balance.success()).isTrue();
        assertThat(balance.availableCash()).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(balance.totalEval()).isEqualByComparingTo(new BigDecimal("1500000"));
    }

    @Test
    @DisplayName("getBalance 실패 응답 → BrokerBalance.failure(사유 담김, 현금 0)")
    void getBalance_failure_mapsFailure() {
        KoreaInvestmentBalanceResponse response = new KoreaInvestmentBalanceResponse();
        response.setRtCd("-1");
        response.setMsg1("조회 실패");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentBalanceResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        BrokerBalance balance = apiClient.getBalance();

        assertThat(balance.success()).isFalse();
        assertThat(balance.availableCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance.message()).isEqualTo("조회 실패");
    }

    @Test
    @DisplayName("getCurrentPrice → BigDecimal(정상)")
    void getCurrentPrice_success_returnsDecimal() {
        KoreaInvestmentPriceResponse response = new KoreaInvestmentPriceResponse();
        response.setRtCd("0");
        KoreaInvestmentPriceResponse.PriceOutput output = new KoreaInvestmentPriceResponse.PriceOutput();
        output.setCurrentPrice("73000");
        response.setOutput(output);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentPriceResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        assertThat(apiClient.getCurrentPrice("005930")).isEqualByComparingTo(new BigDecimal("73000"));
    }

    @Test
    @DisplayName("getCurrentPrice — USD 소수 가격이 절삭 없이 보존된다 (P2 무손실)")
    void getCurrentPrice_usdDecimal_preservesCents() {
        KoreaInvestmentPriceResponse response = new KoreaInvestmentPriceResponse();
        response.setRtCd("0");
        KoreaInvestmentPriceResponse.PriceOutput output = new KoreaInvestmentPriceResponse.PriceOutput();
        output.setCurrentPrice("150.25");
        response.setOutput(output);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentPriceResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        assertThat(apiClient.getCurrentPrice("AAPL")).isEqualByComparingTo(new BigDecimal("150.25"));
    }

    @Test
    @DisplayName("getCurrentPrice 실패 → 0 반환(명시적 0, 조용실패 아님)")
    void getCurrentPrice_failure_returnsZero() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentPriceResponse.class)))
                .thenThrow(new RuntimeException("API 호출 실패"));

        assertThat(apiClient.getCurrentPrice("005930")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("placeBuy(Limit) — 소수 가격이 KRW 정수호가로 내부 변환된다 (ORD_UNPR)")
    void placeBuy_limit_usdDecimalPrice_convertsToKrwInteger() {
        koreaInvestmentConfig.setReal(true);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentOrderResponse ok = new KoreaInvestmentOrderResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), captor.capture(),
                eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        apiClient.placeBuy("AAPL", OrderSpec.limit(new BigDecimal("3"), new BigDecimal("150.25")));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertThat(body.get("ORD_UNPR")).isEqualTo("150");
        assertThat(body.get("ORD_QTY")).isEqualTo("3");
    }

    @Test
    @DisplayName("placeBuy(Notional) — 국내는 현재가 조회로 정수주 환산 후 시장가 라우팅")
    void placeBuy_notional_convertsToIntegerSharesMarketOrder() {
        koreaInvestmentConfig.setReal(true);
        // 현재가 조회 스텁: 100,000원. 주문금액 350,000 → FLOOR(350000/100000)=3주.
        KoreaInvestmentPriceResponse priceRes = new KoreaInvestmentPriceResponse();
        priceRes.setRtCd("0");
        KoreaInvestmentPriceResponse.PriceOutput po = new KoreaInvestmentPriceResponse.PriceOutput();
        po.setCurrentPrice("100000");
        priceRes.setOutput(po);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentPriceResponse.class)))
                .thenReturn(ResponseEntity.ok(priceRes));

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentOrderResponse ok = new KoreaInvestmentOrderResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), captor.capture(),
                eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        OrderResult result = apiClient.placeBuy("005930", OrderSpec.notional(new BigDecimal("350000")));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertThat(body.get("ORD_QTY")).isEqualTo("3");
        assertThat(body.get("ORD_DVSN")).isEqualTo("01"); // 시장가(가격 0)
    }

    @Test
    @DisplayName("주문 API 예외 → OrderResult.failure(사유 담김)")
    void order_apiException_returnsFailure() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(KoreaInvestmentOrderResponse.class)))
                .thenThrow(new RuntimeException("API 호출 실패"));

        OrderResult result = apiClient.placeBuy("005930", OrderSpec.limit(new BigDecimal("10"), new BigDecimal("73000")));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("API 호출 실패");
    }

    @Test
    @DisplayName("계좌번호 파싱 - 정상")
    void accountNumberParsing_validFormat() {
        assertThat(koreaInvestmentConfig.getAccountMain()).isEqualTo("12345678");
        assertThat(koreaInvestmentConfig.getAccountCode()).isEqualTo("01");
    }

    @Test
    @DisplayName("매수 주문 tr_id는 조회(R)가 아닌 주문(U) — 모의투자 회귀방지")
    void placeBuy_usesOrderTrId_notInquiry_mock() {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentOrderResponse ok = new KoreaInvestmentOrderResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), captor.capture(),
                eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        apiClient.placeBuy("005930", OrderSpec.limit(new BigDecimal("10"), new BigDecimal("73000")));

        String trId = captor.getValue().getHeaders().getFirst("tr_id");
        assertThat(trId).isEqualTo("VTTC0802U");
        assertThat(trId).doesNotEndWith("R");
    }

    @Test
    @DisplayName("매수/매도 주문 tr_id — 실전투자 TTTC0802U/TTTC0801U")
    void order_usesRealOrderTrId() {
        koreaInvestmentConfig.setReal(true);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentOrderResponse ok = new KoreaInvestmentOrderResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), captor.capture(),
                eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        apiClient.placeBuy("005930", OrderSpec.limit(new BigDecimal("10"), new BigDecimal("73000")));
        assertThat(captor.getValue().getHeaders().getFirst("tr_id")).isEqualTo("TTTC0802U");

        apiClient.placeSell("005930", OrderSpec.limit(new BigDecimal("10"), new BigDecimal("75000")));
        assertThat(captor.getValue().getHeaders().getFirst("tr_id")).isEqualTo("TTTC0801U");
    }

    @Test
    @DisplayName("잔고 조회 tr_id — 실전 TTTC8434R (조회는 R 유지)")
    void balance_usesRealBalanceTrId() {
        koreaInvestmentConfig.setReal(true);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentBalanceResponse ok = new KoreaInvestmentBalanceResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), captor.capture(),
                eq(KoreaInvestmentBalanceResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        apiClient.getBalance();

        assertThat(captor.getValue().getHeaders().getFirst("tr_id")).isEqualTo("TTTC8434R");
    }

    @Test
    @DisplayName("실전/모의투자 URL 전환")
    void baseUrlSwitching() {
        koreaInvestmentConfig.setReal(false);
        assertThat(koreaInvestmentConfig.getBaseUrl()).contains("29443");

        koreaInvestmentConfig.setReal(true);
        assertThat(koreaInvestmentConfig.getBaseUrl()).contains("9443");
    }
}
