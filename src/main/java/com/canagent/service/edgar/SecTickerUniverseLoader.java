package com.canagent.service.edgar;

import com.canagent.domain.stock.Stock;
import com.canagent.repository.StockRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * SEC {@code company_tickers.json} → Stock 마스터 upsert 로더.
 *
 * <p>JSON 포맷(SEC): {@code { "0": {"cik_str": 320193, "ticker": "AAPL", "title": "Apple Inc."}, ... }}.
 * (구)corp_code_map.json(6자리→corp_code)을 대체하는 US 식별 소스. 그 국내 매핑 파일/DTO는 P9에서 삭제됐다.
 *
 * <p>ticker→Stock 매칭으로 upsert하며 {@code applyUsIdentifiers}로 cik/ticker/exchange/currency를 채운다.
 * exchange는 이 파일에 없어 null(후속: SEC {@code company_tickers_exchange.json} 병합).
 *
 * <p><b>유니버스 필터 훅</b>: 미국 국내 보통주 한정(ADR/foreign issuer 제외)이 목표지만
 * company_tickers.json만으로는 증권유형을 구분할 수 없다. 지금은 {@link #passesUniverseFilter}가
 * 전량 통과시키는 <b>최소 훅</b>이며, 정교화(submissions 메타의 securityType·SIC 기반)는 후속 과제다.
 */
@Service
public class SecTickerUniverseLoader {

    private static final Logger log = LoggerFactory.getLogger(SecTickerUniverseLoader.class);
    private static final String DEFAULT_RESOURCE = "company_tickers.json";

    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecTickerUniverseLoader(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /** 클래스패스의 기본 리소스({@value #DEFAULT_RESOURCE})에서 로딩한다. */
    public int loadFromClasspath() {
        return loadFromClasspath(DEFAULT_RESOURCE);
    }

    public int loadFromClasspath(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return load(in);
        } catch (Exception e) {
            log.error("SEC 유니버스 로드 실패({}): {}", resource, e.getMessage());
            return 0;
        }
    }

    /**
     * company_tickers.json 스트림을 파싱해 Stock을 upsert한다. 반환값은 upsert된 종목 수.
     */
    @Transactional
    public int load(InputStream in) throws Exception {
        JsonNode root = objectMapper.readTree(in);
        int upserted = 0;
        int skipped = 0;

        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            JsonNode row = it.next().getValue();
            String ticker = row.path("ticker").asText(null);
            JsonNode cikNode = row.path("cik_str");
            String title = row.path("title").asText(null);

            if (ticker == null || ticker.isBlank() || cikNode.isMissingNode()) {
                skipped++;
                continue;
            }
            if (!passesUniverseFilter(row)) {
                skipped++;
                continue;
            }

            String cik = String.valueOf(cikNode.asLong());
            String exchange = row.hasNonNull("exchange") ? row.path("exchange").asText() : null;

            upsertStock(ticker.trim(), cik, exchange, title);
            upserted++;
        }

        log.info("SEC 유니버스 로드 완료: {}건 upsert, {}건 skip", upserted, skipped);
        return upserted;
    }

    private void upsertStock(String ticker, String cik, String exchange, String title) {
        // ticker 우선 매칭. ticker 미발견 시에만 cik로 폴백하되, 그 cik 행이 이미 다른 ticker에
        // 귀속돼 있으면 재사용하지 않는다(같은 CIK 공유 이중상장 클래스 GOOG/GOOGL를 별도 행으로 유지).
        Stock stock = stockRepository.findByTicker(ticker)
                .or(() -> stockRepository.findByCik(cik)
                        .filter(s -> s.getTicker() == null || ticker.equals(s.getTicker())))
                .orElse(null);

        if (stock == null) {
            // code는 nullable=false·unique → US 종목은 ticker를 code로 사용. market은 거래소 미상 시 "US".
            String market = exchange != null ? exchange : "US";
            stock = new Stock(ticker, title != null ? title : ticker, market, null);
        } else if (title != null) {
            stock.updateInfo(title, stock.getSector());
        }

        stock.applyUsIdentifiers(ticker, cik, exchange, "USD", stock.getSicCode());
        stockRepository.save(stock);
    }

    /**
     * 유니버스 필터 훅(미국 국내 보통주 한정). 현재는 전량 통과(최소 훅).
     * 정교화는 후속: submissions 메타의 securityType / SIC / foreign filer 플래그 기반 ADR·외국기업 스크린아웃.
     */
    protected boolean passesUniverseFilter(JsonNode row) {
        return true;
    }
}
