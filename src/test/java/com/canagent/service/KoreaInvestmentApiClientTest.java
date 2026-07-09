package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.config.KoreaInvestmentTokenProvider;
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
@DisplayName("한국투자증권 API 클라이언트 단위테스트")
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

    @Test
    @DisplayName("매수 주문 성공")
    void buy_orderSuccess_returnsResponse() {
        // given
        KoreaInvestmentOrderResponse response = new KoreaInvestmentOrderResponse();
        response.setRtCd("0");
        response.setMsg1("주문 성공");
        KoreaInvestmentOrderResponse.OrderOutput output = new KoreaInvestmentOrderResponse.OrderOutput();
        output.setOrderNo("0012345678");
        response.setOutput(output);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        // when
        KoreaInvestmentOrderResponse result = apiClient.buy("005930", 10, new BigDecimal("73000"));

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOrderNo()).isEqualTo("0012345678");
    }

    @Test
    @DisplayName("매도 주문 성공")
    void sell_orderSuccess_returnsResponse() {
        // given
        KoreaInvestmentOrderResponse response = new KoreaInvestmentOrderResponse();
        response.setRtCd("0");
        response.setMsg1("주문 성공");
        KoreaInvestmentOrderResponse.OrderOutput output = new KoreaInvestmentOrderResponse.OrderOutput();
        output.setOrderNo("0012345679");
        response.setOutput(output);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        // when
        KoreaInvestmentOrderResponse result = apiClient.sell("005930", 10, new BigDecimal("75000"));

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOrderNo()).isEqualTo("0012345679");
    }

    @Test
    @DisplayName("잔고 조회 성공")
    void getBalance_success_returnsResponse() {
        // given
        KoreaInvestmentBalanceResponse response = new KoreaInvestmentBalanceResponse();
        response.setRtCd("0");
        response.setMsg1("조회 성공");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentBalanceResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        // when
        KoreaInvestmentBalanceResponse result = apiClient.getBalance();

        // then
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("현재가 조회 성공")
    void getCurrentPrice_success_returnsResponse() {
        // given
        KoreaInvestmentPriceResponse response = new KoreaInvestmentPriceResponse();
        response.setRtCd("0");
        response.setMsg1("조회 성공");
        KoreaInvestmentPriceResponse.PriceOutput output = new KoreaInvestmentPriceResponse.PriceOutput();
        output.setCurrentPrice("73000");
        response.setOutput(output);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentPriceResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        // when
        KoreaInvestmentPriceResponse result = apiClient.getCurrentPrice("005930");

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("73000"));
    }

    @Test
    @DisplayName("현재가 조회 — USD 소수 가격이 절삭 없이 보존된다 (P2 무손실)")
    void getCurrentPrice_usdDecimal_preservesCents() {
        // given: 미국 소수점 가격(예: 150.25). 정수 파싱이었다면 150으로 붕괴.
        KoreaInvestmentPriceResponse response = new KoreaInvestmentPriceResponse();
        response.setRtCd("0");
        KoreaInvestmentPriceResponse.PriceOutput output = new KoreaInvestmentPriceResponse.PriceOutput();
        output.setCurrentPrice("150.25");
        response.setOutput(output);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(KoreaInvestmentPriceResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        // when
        KoreaInvestmentPriceResponse result = apiClient.getCurrentPrice("AAPL");

        // then: 150이 아니라 150.25가 그대로 보존
        assertThat(result.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("150.25"));
    }

    @Test
    @DisplayName("매수 주문 — USD 소수 가격이 ORD_UNPR 전달 경로에서 유실되지 않는다 (P2)")
    void buy_usdDecimalPrice_reachesOrderBody() {
        // given (모의). KIS 국내주문 어댑터는 KRW 정수호가로 반올림하지만, 포트 계약은 무손실.
        koreaInvestmentConfig.setReal(true);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentOrderResponse ok = new KoreaInvestmentOrderResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), captor.capture(),
                eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        // when: 소수 가격 전달(포트 계약이 int였다면 컴파일조차 불가 — 무손실 시그니처 실증)
        apiClient.buy("AAPL", 3, new BigDecimal("150.25"));

        // then: KIS 어댑터는 KRW 정수호가(HALF_UP)로만 내부 변환 → "150"
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertThat(body.get("ORD_UNPR")).isEqualTo("150");
        assertThat(body.get("ORD_QTY")).isEqualTo("3");
    }

    @Test
    @DisplayName("API 호출 실패 시 에러 응답 반환")
    void apiCallFailure_returnsErrorResponse() {
        // given
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(KoreaInvestmentOrderResponse.class)))
                .thenThrow(new RuntimeException("API 호출 실패"));

        // when
        KoreaInvestmentOrderResponse result = apiClient.buy("005930", 10, new BigDecimal("73000"));

        // then
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("계좌번호 파싱 - 정상")
    void accountNumberParsing_validFormat() {
        // then
        assertThat(koreaInvestmentConfig.getAccountMain()).isEqualTo("12345678");
        assertThat(koreaInvestmentConfig.getAccountCode()).isEqualTo("01");
    }

    @Test
    @DisplayName("매수 주문 tr_id는 조회(R)가 아닌 주문(U) — 모의투자 회귀방지")
    void buy_usesOrderTrId_notInquiry_mock() {
        // given (setUp: setReal(false) → 모의)
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentOrderResponse ok = new KoreaInvestmentOrderResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), captor.capture(),
                eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        // when
        apiClient.buy("005930", 10, new BigDecimal("73000"));

        // then — 주문은 U 접미사. R이면 게이트웨이 EGW00202("GW라우팅 오류") 발생
        String trId = captor.getValue().getHeaders().getFirst("tr_id");
        assertThat(trId).isEqualTo("VTTC0802U");
        assertThat(trId).doesNotEndWith("R");
    }

    @Test
    @DisplayName("매수/매도 주문 tr_id — 실전투자 TTTC0802U/TTTC0801U")
    void order_usesRealOrderTrId() {
        // given
        koreaInvestmentConfig.setReal(true);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentOrderResponse ok = new KoreaInvestmentOrderResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), captor.capture(),
                eq(KoreaInvestmentOrderResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        // when / then
        apiClient.buy("005930", 10, new BigDecimal("73000"));
        assertThat(captor.getValue().getHeaders().getFirst("tr_id")).isEqualTo("TTTC0802U");

        apiClient.sell("005930", 10, new BigDecimal("75000"));
        assertThat(captor.getValue().getHeaders().getFirst("tr_id")).isEqualTo("TTTC0801U");
    }

    @Test
    @DisplayName("잔고 조회 tr_id — 실전 TTTC8434R (조회는 R 유지)")
    void balance_usesRealBalanceTrId() {
        // given
        koreaInvestmentConfig.setReal(true);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        KoreaInvestmentBalanceResponse ok = new KoreaInvestmentBalanceResponse();
        ok.setRtCd("0");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), captor.capture(),
                eq(KoreaInvestmentBalanceResponse.class)))
                .thenReturn(ResponseEntity.ok(ok));

        // when
        apiClient.getBalance();

        // then — 잔고는 조회(R)가 맞음
        assertThat(captor.getValue().getHeaders().getFirst("tr_id")).isEqualTo("TTTC8434R");
    }

    @Test
    @DisplayName("실전/모의투자 URL 전환")
    void baseUrlSwitching() {
        // given
        koreaInvestmentConfig.setReal(false);
        assertThat(koreaInvestmentConfig.getBaseUrl()).contains("29443");

        koreaInvestmentConfig.setReal(true);
        assertThat(koreaInvestmentConfig.getBaseUrl()).contains("9443");
    }
}
