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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

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
        KoreaInvestmentOrderResponse result = apiClient.buy("005930", 10, 73000);

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
        KoreaInvestmentOrderResponse result = apiClient.sell("005930", 10, 75000);

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
                eq(KoreaInvestmentBalanceResponse.class), anyMap()))
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
                eq(KoreaInvestmentPriceResponse.class), anyMap()))
                .thenReturn(ResponseEntity.ok(response));

        // when
        KoreaInvestmentPriceResponse result = apiClient.getCurrentPrice("005930");

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCurrentPrice()).isEqualTo(73000);
    }

    @Test
    @DisplayName("API 호출 실패 시 에러 응답 반환")
    void apiCallFailure_returnsErrorResponse() {
        // given
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(KoreaInvestmentOrderResponse.class)))
                .thenThrow(new RuntimeException("API 호출 실패"));

        // when
        KoreaInvestmentOrderResponse result = apiClient.buy("005930", 10, 73000);

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
    @DisplayName("실전/모의투자 URL 전환")
    void baseUrlSwitching() {
        // given
        koreaInvestmentConfig.setReal(false);
        assertThat(koreaInvestmentConfig.getBaseUrl()).contains("29443");

        koreaInvestmentConfig.setReal(true);
        assertThat(koreaInvestmentConfig.getBaseUrl()).contains("9443");
    }
}
