package com.canagent.service.edgar;

import com.canagent.domain.stock.Stock;
import com.canagent.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SEC 유니버스 로더 단위테스트 (검증 d: ticker→cik)")
class SecTickerUniverseLoaderTest {

    @Mock
    private StockRepository stockRepository;

    @Test
    @DisplayName("company_tickers.json → Stock upsert, ticker/cik/currency 매핑")
    void loadsTickersAndCik() throws Exception {
        SecTickerUniverseLoader loader = new SecTickerUniverseLoader(stockRepository);
        when(stockRepository.findByTicker(any())).thenReturn(Optional.empty());
        when(stockRepository.findByCik(any())).thenReturn(Optional.empty());

        int count;
        try (InputStream in = new ClassPathResource("edgar/company_tickers-sample.json").getInputStream()) {
            count = loader.load(in);
        }

        assertThat(count).isEqualTo(3);

        ArgumentCaptor<Stock> saved = ArgumentCaptor.forClass(Stock.class);
        verify(stockRepository, times(3)).save(saved.capture());

        List<Stock> stocks = saved.getAllValues();
        Stock apple = stocks.stream().filter(s -> "AAPL".equals(s.getTicker())).findFirst().orElseThrow();
        // company_tickers.json은 cik를 패딩 없는 정수로 제공 → 그대로 문자열화
        assertThat(apple.getCik()).isEqualTo("320193");
        assertThat(apple.getCurrency()).isEqualTo("USD");
        assertThat(apple.getCode()).isEqualTo("AAPL");
        assertThat(apple.getName()).isEqualTo("Apple Inc.");

        Stock zeta = stocks.stream().filter(s -> "ZZT".equals(s.getTicker())).findFirst().orElseThrow();
        assertThat(zeta.getCik()).isEqualTo("1800001");
    }

    @Test
    @DisplayName("기존 종목은 cik/식별자 갱신(upsert)")
    void updatesExistingStock() throws Exception {
        SecTickerUniverseLoader loader = new SecTickerUniverseLoader(stockRepository);
        Stock existing = new Stock("AAPL", "Apple Inc.", "NASDAQ", null);
        when(stockRepository.findByTicker("AAPL")).thenReturn(Optional.of(existing));
        when(stockRepository.findByTicker("MSFT")).thenReturn(Optional.empty());
        when(stockRepository.findByTicker("ZZT")).thenReturn(Optional.empty());
        when(stockRepository.findByCik(any())).thenReturn(Optional.empty());

        try (InputStream in = new ClassPathResource("edgar/company_tickers-sample.json").getInputStream()) {
            loader.load(in);
        }

        assertThat(existing.getCik()).isEqualTo("320193");
    }

    @Test
    @DisplayName("같은 CIK 공유 이중상장 클래스는 별도 종목으로 upsert(GOOG/GOOGL 병합 방지)")
    void sameCikDifferentTicker_notMerged() throws Exception {
        SecTickerUniverseLoader loader = new SecTickerUniverseLoader(stockRepository);
        // GOOGL은 이미 적재돼 CIK 1652044를 점유. GOOG(같은 CIK, 다른 ticker)가 들어와도
        // findByCik 폴백이 GOOGL 행을 재사용하면 안 된다.
        Stock googl = new Stock("GOOGL", "Alphabet Inc.", "NASDAQ", null);
        googl.applyUsIdentifiers("GOOGL", "1652044", "NASDAQ", "USD", null);
        when(stockRepository.findByTicker("GOOG")).thenReturn(Optional.empty());
        when(stockRepository.findByCik("1652044")).thenReturn(Optional.of(googl));

        InputStream in = new java.io.ByteArrayInputStream(
                ("{\"0\":{\"cik_str\":1652044,\"ticker\":\"GOOG\","
                        + "\"title\":\"Alphabet Inc.\",\"exchange\":\"NASDAQ\"}}").getBytes());
        int count = loader.load(in);

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<Stock> saved = ArgumentCaptor.forClass(Stock.class);
        verify(stockRepository).save(saved.capture());
        // 저장된 것은 GOOGL 행이 아니라 새 GOOG 행이어야 한다
        assertThat(saved.getValue().getTicker()).isEqualTo("GOOG");
        assertThat(saved.getValue()).isNotSameAs(googl);
        // GOOGL 행은 GOOG로 덮어써지지 않고 그대로 유지
        assertThat(googl.getTicker()).isEqualTo("GOOGL");
    }
}
