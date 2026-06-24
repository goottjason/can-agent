package com.canagent.service;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.repository.FinancialStatementRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.dto.DartFinancialDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DART 데이터 동기화 서비스 단위테스트")
class DartDataSyncServiceTest {

    @Mock
    private DartApiClient dartApiClient;
    @Mock
    private StockRepository stockRepository;
    @Mock
    private FinancialStatementRepository financialStatementRepository;

    @InjectMocks
    private DartDataSyncService dartDataSyncService;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    @Test
    @DisplayName("재무제표 동기화 - 정상 저장")
    void syncFinancialStatements_validData_savesSuccessfully() {
        // given
        when(stockRepository.findByCode("005930")).thenReturn(Optional.of(stock));

        DartFinancialDTO revenueDto = new DartFinancialDTO();
        revenueDto.setAccountName("매출액");
        revenueDto.setCurrentAmount("50,000,000,000");

        DartFinancialDTO operatingIncomeDto = new DartFinancialDTO();
        operatingIncomeDto.setAccountName("영업이익");
        operatingIncomeDto.setCurrentAmount("5,000,000,000");

        when(dartApiClient.getFinancialStatements(eq("005930"), eq("2024"), eq("1")))
                .thenReturn(List.of(revenueDto, operatingIncomeDto));
        when(financialStatementRepository.findByStockIdAndFiscalYearAndFiscalQuarter(
                any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());

        // when
        int result = dartDataSyncService.syncFinancialStatements("005930", "2024", "1");

        // then
        assertThat(result).isEqualTo(1);
        verify(financialStatementRepository, times(1)).save(any(FinancialStatement.class));
    }

    @Test
    @DisplayName("재무제표 동기화 - 종목 미등록")
    void syncFinancialStatements_stockNotFound_returnsZero() {
        // given
        when(stockRepository.findByCode("999999")).thenReturn(Optional.empty());

        // when
        int result = dartDataSyncService.syncFinancialStatements("999999", "2024", "1");

        // then
        assertThat(result).isEqualTo(0);
        verify(dartApiClient, never()).getFinancialStatements(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("재무제표 동기화 - 데이터 없음")
    void syncFinancialStatements_noData_returnsZero() {
        // given
        when(stockRepository.findByCode("005930")).thenReturn(Optional.of(stock));
        when(dartApiClient.getFinancialStatements(eq("005930"), eq("2024"), eq("1")))
                .thenReturn(Collections.emptyList());

        // when
        int result = dartDataSyncService.syncFinancialStatements("005930", "2024", "1");

        // then
        assertThat(result).isEqualTo(0);
    }

    @Test
    @DisplayName("재무제표 동기화 - 기존 데이터 업데이트")
    void syncFinancialStatements_existingData_updates() {
        // given
        when(stockRepository.findByCode("005930")).thenReturn(Optional.of(stock));

        DartFinancialDTO revenueDto = new DartFinancialDTO();
        revenueDto.setAccountName("매출액");
        revenueDto.setCurrentAmount("60,000,000,000");

        when(dartApiClient.getFinancialStatements(eq("005930"), eq("2024"), eq("1")))
                .thenReturn(List.of(revenueDto));

        FinancialStatement existing = MockDataFactory.createGrowingEarnings(stock);
        when(financialStatementRepository.findByStockIdAndFiscalYearAndFiscalQuarter(
                any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(existing));

        // when
        int result = dartDataSyncService.syncFinancialStatements("005930", "2024", "1");

        // then
        assertThat(result).isEqualTo(1);
        verify(financialStatementRepository, times(1)).save(any(FinancialStatement.class));
    }

    @Test
    @DisplayName("전체 활성 종목 재무제표 동기화")
    void syncAllActiveStocks_multipleStocks_syncsAll() {
        // given
        Stock stock2 = MockDataFactory.createNaverStock();
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock, stock2));
        when(stockRepository.findByCode("005930")).thenReturn(Optional.of(stock));
        when(stockRepository.findByCode("035420")).thenReturn(Optional.of(stock2));
        when(dartApiClient.getFinancialStatements(anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        // when
        int totalCount = dartDataSyncService.syncAllActiveStocks("2024", "1");

        // then
        assertThat(totalCount).isEqualTo(0);
        verify(dartApiClient, times(2)).getFinancialStatements(anyString(), anyString(), anyString());
    }
}
