package com.canagent.service;

import com.canagent.domain.stock.Stock;
import com.canagent.repository.StockRepository;
import com.canagent.service.dto.KrxCorpDTO;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockService 단위테스트")
class StockServiceTest {

    @Mock
    private StockRepository stockRepository;
    @Mock
    private KrxApiClient krxApiClient;

    @InjectMocks
    private StockService stockService;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = new Stock("005930", "삼성전자", "KOSPI", "반도체");
    }

    @Test
    @DisplayName("전체 활성 종목 조회")
    void getAllActiveStocks_returnsActiveStocks() {
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));

        List<Stock> result = stockService.getAllActiveStocks();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("종목 코드로 조회")
    void getStockByCode_found() {
        when(stockRepository.findByCode("005930")).thenReturn(Optional.of(stock));

        Optional<Stock> result = stockService.getStockByCode("005930");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("삼성전자");
    }

    @Test
    @DisplayName("종목 검색")
    void searchStocks_withKeyword() {
        when(stockRepository.findByCodeContainingOrNameContainingAndActiveTrue("삼성", "삼성"))
                .thenReturn(List.of(stock));

        List<Stock> result = stockService.searchStocks("삼성");

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("종목 검색 - 키워드 없음")
    void searchStocks_noKeyword() {
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));

        List<Stock> result = stockService.searchStocks("");

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("종목 등록 - 성공")
    void registerStock_success() {
        when(stockRepository.existsByCode("000660")).thenReturn(false);
        when(stockRepository.save(any(Stock.class))).thenReturn(stock);

        Stock result = stockService.registerStock("000660", "SK하이닉스", "KOSPI", "반도체");

        assertThat(result).isNotNull();
        verify(stockRepository).save(any(Stock.class));
    }

    @Test
    @DisplayName("종목 등록 - 중복 코드")
    void registerStock_duplicateCode() {
        when(stockRepository.existsByCode("005930")).thenReturn(true);

        assertThatThrownBy(() -> stockService.registerStock("005930", "삼성전자", "KOSPI", "반도체"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 등록된 종목");
    }

    @Test
    @DisplayName("KRX에서 종목 등록 - 신규")
    void registerStockFromKrx_new() {
        KrxCorpDTO krxCorp = new KrxCorpDTO();
        krxCorp.setStockCode("000660");
        krxCorp.setItemName("SK하이닉스");
        krxCorp.setMarketCategory("KOSPI");

        when(stockRepository.findByCode("000660")).thenReturn(Optional.empty());
        when(stockRepository.save(any(Stock.class))).thenReturn(new Stock("000660", "SK하이닉스", "KOSPI", "KOSPI"));

        Stock result = stockService.registerStockFromKrx(krxCorp);

        assertThat(result.getCode()).isEqualTo("000660");
        verify(stockRepository).save(any(Stock.class));
    }

    @Test
    @DisplayName("KRX에서 종목 등록 - 기존 종목 재활성화")
    void registerStockFromKrx_existing() {
        KrxCorpDTO krxCorp = new KrxCorpDTO();
        krxCorp.setStockCode("005930");
        krxCorp.setItemName("삼성전자");
        krxCorp.setMarketCategory("KOSPI");

        Stock inactiveStock = new Stock("005930", "삼성전자", "KOSPI", "반도체");
        inactiveStock.deactivate();

        when(stockRepository.findByCode("005930")).thenReturn(Optional.of(inactiveStock));
        when(stockRepository.findById(any())).thenReturn(Optional.of(inactiveStock));
        when(stockRepository.save(any(Stock.class))).thenReturn(inactiveStock);

        Stock result = stockService.registerStockFromKrx(krxCorp);

        assertThat(result.getCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("종목 비활성화")
    void deactivateStock_success() {
        when(stockRepository.findById(1L)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenReturn(stock);

        Stock result = stockService.deactivateStock(1L);

        assertThat(result.isActive()).isFalse();
    }

    @Test
    @DisplayName("종목 비활성화 - 존재하지 않는 종목")
    void deactivateStock_notFound() {
        when(stockRepository.findById(eq(999L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockService.deactivateStock(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("종목을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("시장별 종목 수 조회")
    void getStockCountByMarket() {
        when(stockRepository.countByMarketAndActiveTrue("KOSPI")).thenReturn(100L);

        long count = stockService.getStockCountByMarket("KOSPI");

        assertThat(count).isEqualTo(100L);
    }
}
