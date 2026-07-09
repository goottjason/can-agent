package com.canagent.worker;

import com.canagent.MockDataFactory;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.repository.PortfolioRepository;
import com.canagent.service.KoreaInvestmentApiClient;
import com.canagent.service.dto.KoreaInvestmentPriceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("포트폴리오 스케줄러 단위테스트")
class PortfolioSchedulerTest {

    @Mock
    private PortfolioRepository portfolioRepository;
    @Mock
    private KoreaInvestmentApiClient koreaInvestmentApiClient;

    @InjectMocks
    private PortfolioScheduler portfolioScheduler;

    private Stock samsung;
    private Stock naver;
    private Portfolio portfolioSamsung;
    private Portfolio portfolioNaver;

    @BeforeEach
    void setUp() {
        samsung = MockDataFactory.createSamsungStock();
        naver = MockDataFactory.createNaverStock();
        portfolioSamsung = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        portfolioNaver = MockDataFactory.createPortfolio(naver, 5, new BigDecimal("300000"));
    }

    @Test
    @DisplayName("현재가 갱신 - 정상 업데이트")
    void updatePortfolioPrices_validResponse_updatesPrice() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(portfolioSamsung));

        KoreaInvestmentPriceResponse response = mock(KoreaInvestmentPriceResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getCurrentPrice()).thenReturn(new BigDecimal("75000"));
        when(koreaInvestmentApiClient.getCurrentPrice("005930")).thenReturn(response);

        portfolioScheduler.updatePortfolioPrices();

        assertThat(portfolioSamsung.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("75000"));
        verify(portfolioRepository, times(1)).save(portfolioSamsung);
    }

    @Test
    @DisplayName("현재가 갱신 - API 실패 시 저장 안함")
    void updatePortfolioPrices_apiFailure_doesNotSave() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(portfolioSamsung));
        when(koreaInvestmentApiClient.getCurrentPrice("005930")).thenThrow(new RuntimeException("API 오류"));

        portfolioScheduler.updatePortfolioPrices();

        assertThat(portfolioSamsung.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("70000"));
        verify(portfolioRepository, never()).save(any());
    }

    @Test
    @DisplayName("현재가 갱신 - 응답 실패 시 저장 안함")
    void updatePortfolioPrices_failedResponse_doesNotSave() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(portfolioSamsung));

        KoreaInvestmentPriceResponse response = mock(KoreaInvestmentPriceResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(koreaInvestmentApiClient.getCurrentPrice("005930")).thenReturn(response);

        portfolioScheduler.updatePortfolioPrices();

        verify(portfolioRepository, never()).save(any());
    }

    @Test
    @DisplayName("현재가 갱신 - 가격이 0일 때 저장 안함")
    void updatePortfolioPrices_zeroPrice_doesNotSave() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(portfolioSamsung));

        KoreaInvestmentPriceResponse response = mock(KoreaInvestmentPriceResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getCurrentPrice()).thenReturn(BigDecimal.ZERO);
        when(koreaInvestmentApiClient.getCurrentPrice("005930")).thenReturn(response);

        portfolioScheduler.updatePortfolioPrices();

        verify(portfolioRepository, never()).save(any());
    }

    @Test
    @DisplayName("현재가 갱신 - 여러 종목 업데이트")
    void updatePortfolioPrices_multipleStocks_updatesAll() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(portfolioSamsung, portfolioNaver));

        KoreaInvestmentPriceResponse responseSamsung = mock(KoreaInvestmentPriceResponse.class);
        when(responseSamsung.isSuccess()).thenReturn(true);
        when(responseSamsung.getCurrentPrice()).thenReturn(new BigDecimal("75000"));

        KoreaInvestmentPriceResponse responseNaver = mock(KoreaInvestmentPriceResponse.class);
        when(responseNaver.isSuccess()).thenReturn(true);
        when(responseNaver.getCurrentPrice()).thenReturn(new BigDecimal("320000"));

        when(koreaInvestmentApiClient.getCurrentPrice("005930")).thenReturn(responseSamsung);
        when(koreaInvestmentApiClient.getCurrentPrice("035420")).thenReturn(responseNaver);

        portfolioScheduler.updatePortfolioPrices();

        assertThat(portfolioSamsung.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("75000"));
        assertThat(portfolioNaver.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("320000"));
        verify(portfolioRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("현재가 갱신 - 활성 포트폴리오 없을 때")
    void updatePortfolioPrices_noPortfolios_doesNothing() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(Collections.emptyList());

        portfolioScheduler.updatePortfolioPrices();

        verify(koreaInvestmentApiClient, never()).getCurrentPrice(anyString());
        verify(portfolioRepository, never()).save(any());
    }

    @Test
    @DisplayName("종가 갱신 - 정상 업데이트")
    void updateClosePrices_validResponse_updatesPrice() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(portfolioSamsung));

        KoreaInvestmentPriceResponse response = mock(KoreaInvestmentPriceResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getCurrentPrice()).thenReturn(new BigDecimal("76000"));
        when(koreaInvestmentApiClient.getCurrentPrice("005930")).thenReturn(response);

        portfolioScheduler.updateClosePrices();

        assertThat(portfolioSamsung.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("76000"));
        verify(portfolioRepository, times(1)).save(portfolioSamsung);
    }

    @Test
    @DisplayName("종가 갱신 - API 실패 시 저장 안함")
    void updateClosePrices_apiFailure_doesNotSave() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(portfolioSamsung));
        when(koreaInvestmentApiClient.getCurrentPrice("005930")).thenThrow(new RuntimeException("API 오류"));

        portfolioScheduler.updateClosePrices();

        verify(portfolioRepository, never()).save(any());
    }

    @Test
    @DisplayName("종가 갱신 - 여러 종목 업데이트")
    void updateClosePrices_multipleStocks_updatesAll() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(portfolioSamsung, portfolioNaver));

        KoreaInvestmentPriceResponse responseSamsung = mock(KoreaInvestmentPriceResponse.class);
        when(responseSamsung.isSuccess()).thenReturn(true);
        when(responseSamsung.getCurrentPrice()).thenReturn(new BigDecimal("76000"));

        KoreaInvestmentPriceResponse responseNaver = mock(KoreaInvestmentPriceResponse.class);
        when(responseNaver.isSuccess()).thenReturn(true);
        when(responseNaver.getCurrentPrice()).thenReturn(new BigDecimal("330000"));

        when(koreaInvestmentApiClient.getCurrentPrice("005930")).thenReturn(responseSamsung);
        when(koreaInvestmentApiClient.getCurrentPrice("035420")).thenReturn(responseNaver);

        portfolioScheduler.updateClosePrices();

        assertThat(portfolioSamsung.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("76000"));
        assertThat(portfolioNaver.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("330000"));
        verify(portfolioRepository, times(2)).save(any());
    }
}
