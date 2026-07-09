package com.canagent.service.edgar;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.repository.FinancialStatementRepository;
import com.canagent.repository.StockRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EDGAR 재무 어댑터 단위테스트")
class EdgarFinancialsAdapterTest {

    @Mock
    private EdgarClient edgarClient;
    @Mock
    private StockRepository stockRepository;
    @Mock
    private FinancialStatementRepository financialStatementRepository;

    private EdgarFinancialsAdapter adapter;
    private JsonNode sampleFacts;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new EdgarFinancialsAdapter(
                edgarClient, new EdgarCompanyFactsParser(), stockRepository, financialStatementRepository);
        sampleFacts = new ObjectMapper().readTree(
                new ClassPathResource("edgar/companyfacts-sample.json").getInputStream());
    }

    private Stock stockWithCik(String cik) {
        Stock s = MockDataFactory.createSamsungStock();
        s.setCik(cik);
        return s;
    }

    @Test
    @DisplayName("active + cik 종목의 companyfacts를 파싱해 전 기간 upsert")
    void syncAllActiveStocks_upsertsParsedStatements() {
        Stock stock = stockWithCik("1800001");
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));
        when(edgarClient.getCompanyFacts("1800001")).thenReturn(sampleFacts);
        when(financialStatementRepository.findByStockIdAndFiscalYearAndFiscalQuarterIsNull(any(), anyInt()))
                .thenReturn(Optional.empty());
        when(financialStatementRepository.findByStockIdAndFiscalYearAndFiscalQuarter(any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());

        int count = adapter.syncAllActiveStocks(null, null);

        // 파서가 만드는 5개 기간(연간 2 + 분기 3) 모두 upsert
        assertThat(count).isEqualTo(5);
        verify(financialStatementRepository, times(5)).save(any(FinancialStatement.class));
    }

    @Test
    @DisplayName("year 힌트 이후 연도만 upsert")
    void syncAllActiveStocks_respectsYearHint() {
        Stock stock = stockWithCik("1800001");
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));
        when(edgarClient.getCompanyFacts("1800001")).thenReturn(sampleFacts);
        when(financialStatementRepository.findByStockIdAndFiscalYearAndFiscalQuarter(any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());

        int count = adapter.syncAllActiveStocks("2024", null);

        // fiscalYear >= 2024: 2024Q1, 2024Q2 두 건만
        assertThat(count).isEqualTo(2);
        verify(financialStatementRepository, times(2)).save(any(FinancialStatement.class));
    }

    @Test
    @DisplayName("cik 없는 종목은 EDGAR 호출 없이 skip")
    void syncAllActiveStocks_skipsNoCik() {
        Stock noCik = MockDataFactory.createNaverStock(); // cik null
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(noCik));

        int count = adapter.syncAllActiveStocks(null, null);

        assertThat(count).isEqualTo(0);
        verify(edgarClient, never()).getCompanyFacts(any());
        verify(financialStatementRepository, never()).save(any());
    }

    @Test
    @DisplayName("companyfacts 조회 실패(null)면 해당 종목 0건")
    void syncAllActiveStocks_nullFacts() {
        Stock stock = stockWithCik("1800001");
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));
        when(edgarClient.getCompanyFacts("1800001")).thenReturn(null);

        int count = adapter.syncAllActiveStocks(null, null);

        assertThat(count).isEqualTo(0);
        verify(financialStatementRepository, never()).save(any());
    }

    @Test
    @DisplayName("importFromJsonFile: JSON의 cik로 종목 매칭 후 적재")
    void importFromJsonFile_matchesByCik() throws Exception {
        String path = new ClassPathResource("edgar/companyfacts-sample.json").getFile().getAbsolutePath();
        Stock stock = stockWithCik("1800001");
        when(stockRepository.findByCik("1800001")).thenReturn(Optional.of(stock));
        when(financialStatementRepository.findByStockIdAndFiscalYearAndFiscalQuarterIsNull(any(), anyInt()))
                .thenReturn(Optional.empty());
        when(financialStatementRepository.findByStockIdAndFiscalYearAndFiscalQuarter(any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());

        int count = adapter.importFromJsonFile(path);

        assertThat(count).isEqualTo(5);
        verify(financialStatementRepository, times(5)).save(any(FinancialStatement.class));
    }

    @Test
    @DisplayName("importFromJsonFile: 매칭 종목 없으면 0건")
    void importFromJsonFile_noStock() throws Exception {
        String path = new ClassPathResource("edgar/companyfacts-sample.json").getFile().getAbsolutePath();
        when(stockRepository.findByCik(any())).thenReturn(Optional.empty());

        int count = adapter.importFromJsonFile(path);

        assertThat(count).isEqualTo(0);
        verify(financialStatementRepository, never()).save(any());
    }
}
