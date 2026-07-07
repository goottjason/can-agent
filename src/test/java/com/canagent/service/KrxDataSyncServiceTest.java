package com.canagent.service;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.dto.KrxPriceDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KRX 데이터 동기화 서비스 단위테스트")
class KrxDataSyncServiceTest {

    @Mock
    private KrxApiClient krxApiClient;
    @Mock
    private StockRepository stockRepository;
    @Mock
    private StockPriceRepository stockPriceRepository;

    @InjectMocks
    private KrxDataSyncService krxDataSyncService;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    @Test
    @DisplayName("주가 데이터 동기화 - 정상 저장")
    void syncDailyPrices_validData_savesSuccessfully() {
        // given
        when(stockRepository.findByCode("005930")).thenReturn(Optional.of(stock));

        KrxPriceDTO dto = new KrxPriceDTO();
        dto.setBaseDate("20240115");
        dto.setStockCode("005930");
        dto.setClosingPrice("73000");
        dto.setOpeningPrice("72000");
        dto.setHighPrice("73500");
        dto.setLowPrice("71500");
        dto.setTradingQuantity("1000000");
        dto.setFluctuationRate("1.39");

        when(krxApiClient.getDailyPrices(eq("005930"), anyString(), anyString()))
                .thenReturn(List.of(dto));
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(), any()))
                .thenReturn(Collections.emptyList());

        // when
        int savedCount = krxDataSyncService.syncDailyPrices(
                "005930", LocalDate.of(2024, 1, 15), LocalDate.of(2024, 1, 15));

        // then
        assertThat(savedCount).isEqualTo(1);
        verify(stockPriceRepository, times(1)).save(any(StockPrice.class));
    }

    @Test
    @DisplayName("주가 데이터 동기화 - 종목 미등록")
    void syncDailyPrices_stockNotFound_returnsZero() {
        // given
        when(stockRepository.findByCode("999999")).thenReturn(Optional.empty());

        // when
        int savedCount = krxDataSyncService.syncDailyPrices(
                "999999", LocalDate.of(2024, 1, 15), LocalDate.of(2024, 1, 15));

        // then
        assertThat(savedCount).isEqualTo(0);
        verify(krxApiClient, never()).getDailyPrices(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("주가 데이터 동기화 - 중복 데이터 스킵")
    void syncDailyPrices_duplicateData_skips() {
        // given
        when(stockRepository.findByCode("005930")).thenReturn(Optional.of(stock));

        KrxPriceDTO dto = new KrxPriceDTO();
        dto.setBaseDate("20240115");
        dto.setStockCode("005930");
        dto.setClosingPrice("73000");
        dto.setOpeningPrice("72000");
        dto.setHighPrice("73500");
        dto.setLowPrice("71500");
        dto.setTradingQuantity("1000000");

        when(krxApiClient.getDailyPrices(eq("005930"), anyString(), anyString()))
                .thenReturn(List.of(dto));

        StockPrice existingPrice = MockDataFactory.createStockPrice(
                stock, LocalDate.of(2024, 1, 15),
                new BigDecimal("72000"), new BigDecimal("73500"),
                new BigDecimal("71500"), new BigDecimal("73000"), 1000000L);
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(), any()))
                .thenReturn(List.of(existingPrice));

        // when
        int savedCount = krxDataSyncService.syncDailyPrices(
                "005930", LocalDate.of(2024, 1, 15), LocalDate.of(2024, 1, 15));

        // then
        assertThat(savedCount).isEqualTo(0);
        verify(stockPriceRepository, never()).save(any(StockPrice.class));
    }

    @Test
    @DisplayName("전체 활성 종목 동기화")
    void syncAllActiveStocks_multipleStocks_syncsAll() {
        // given — syncAllActiveStocks는 종목별 조회가 아니라 날짜별 전종목 배치(getAllDailyPrices) 사용.
        Stock stock2 = MockDataFactory.createNaverStock();
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock, stock2));
        when(krxApiClient.getAllDailyPrices(anyString()))
                .thenReturn(Collections.emptyList());

        // when — 단일 거래일 조회 → 배치 1회 호출
        int totalSaved = krxDataSyncService.syncAllActiveStocks(
                LocalDate.of(2024, 1, 15), LocalDate.of(2024, 1, 15));

        // then
        assertThat(totalSaved).isEqualTo(0);
        verify(krxApiClient, times(1)).getAllDailyPrices(anyString());
        verify(krxApiClient, never()).getDailyPrices(anyString(), anyString(), anyString());
    }
}
