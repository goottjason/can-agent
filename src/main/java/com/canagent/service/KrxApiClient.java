package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.service.dto.KrxCorpDTO;
import com.canagent.service.dto.KrxPriceDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;

@Service
public class KrxApiClient {

    private static final Logger log = LoggerFactory.getLogger(KrxApiClient.class);

    private final RestTemplate restTemplate;
    private final ApiConfig apiConfig;

    public KrxApiClient(RestTemplate restTemplate, ApiConfig apiConfig) {
        this.restTemplate = restTemplate;
        this.apiConfig = apiConfig;
    }

    public List<KrxPriceDTO> getDailyPrices(String stockCode, String startDate, String endDate) {
        String url = UriComponentsBuilder.fromHttpUrl(apiConfig.getKrx().getBaseUrl())
                .queryParam("serviceKey", apiConfig.getKrx().getKey())
                .queryParam("numOfRows", "100")
                .queryParam("pageNo", "1")
                .queryParam("resultType", "json")
                .queryParam("basDt", startDate)
                .queryParam("isnCd", stockCode)
                .queryParam("beginBasDt", startDate)
                .queryParam("endBasDt", endDate)
                .toUriString();

        try {
            var response = restTemplate.getForObject(url, java.util.Map.class);
            if (response != null) {
                var body = (java.util.Map<String, Object>) response.get("response");
                if (body != null) {
                    var items = (java.util.Map<String, Object>) body.get("body");
                    if (items != null) {
                        var itemList = (java.util.List<java.util.Map<String, String>>) items.get("items");
                        if (itemList != null) {
                            return itemList.stream()
                                    .map(this::mapToPriceDTO)
                                    .toList();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("KRX API 호출 실패: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    public List<KrxCorpDTO> getStockList(String baseDate, int pageNo, int numOfRows) {
        String url = UriComponentsBuilder.fromHttpUrl(apiConfig.getKrx().getStockListUrl())
                .queryParam("serviceKey", apiConfig.getKrx().getKey())
                .queryParam("numOfRows", String.valueOf(numOfRows))
                .queryParam("pageNo", String.valueOf(pageNo))
                .queryParam("resultType", "json")
                .queryParam("basDt", baseDate)
                .toUriString();

        try {
            var response = restTemplate.getForObject(url, java.util.Map.class);
            if (response != null) {
                var body = (java.util.Map<String, Object>) response.get("response");
                if (body != null) {
                    var header = (java.util.Map<String, Object>) body.get("header");
                    int resultCode = header != null ? ((Number) header.get("resultCode")).intValue() : -1;
                    if (resultCode != 0) {
                        log.warn("KRX 종목 리스트 API 응답 오류: {}", header != null ? header.get("resultMsg") : "unknown");
                        return Collections.emptyList();
                    }

                    var items = (java.util.Map<String, Object>) body.get("body");
                    if (items != null) {
                        var itemList = (java.util.List<java.util.Map<String, String>>) items.get("items");
                        if (itemList != null) {
                            return itemList.stream()
                                    .map(this::mapToCorpDTO)
                                    .toList();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("KRX 종목 리스트 API 호출 실패: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    public List<KrxCorpDTO> getAllStockList(String baseDate) {
        java.util.List<KrxCorpDTO> allStocks = new java.util.ArrayList<>();
        int pageNo = 1;
        int numOfRows = 5000;

        while (true) {
            List<KrxCorpDTO> batch = getStockList(baseDate, pageNo, numOfRows);
            if (batch.isEmpty()) {
                break;
            }
            allStocks.addAll(batch);
            if (batch.size() < numOfRows) {
                break;
            }
            pageNo++;
        }

        log.info("KRX 종목 리스트 조회 완료: {}건", allStocks.size());
        return allStocks;
    }

    private KrxPriceDTO mapToPriceDTO(java.util.Map<String, String> item) {
        var dto = new KrxPriceDTO();
        dto.setBaseDate(item.get("basDt"));
        dto.setStockCode(item.get("srtnCd"));
        dto.setIsinCode(item.get("isinCd"));
        dto.setItemName(item.get("itmsNm"));
        dto.setClosingPrice(item.get("clpr"));
        dto.setChangeAmount(item.get("vs"));
        dto.setFluctuationRate(item.get("fltRt"));
        dto.setOpeningPrice(item.get("mkp"));
        dto.setHighPrice(item.get("hipr"));
        dto.setLowPrice(item.get("lopr"));
        dto.setTradingQuantity(item.get("trqu"));
        dto.setTradingPrice(item.get("trP"));
        dto.setListedStockCount(item.get("lstgStCnt"));
        return dto;
    }

    private KrxCorpDTO mapToCorpDTO(java.util.Map<String, String> item) {
        var dto = new KrxCorpDTO();
        dto.setStockCode(item.get("srtnCd"));
        dto.setItemName(item.get("itmsNm"));
        dto.setMarketCategory(item.get("mrktCtg"));
        dto.setMarketValue(item.get("mktpVs"));
        dto.setClosingPrice(item.get("clpr"));
        dto.setChangeAmount(item.get("vs"));
        dto.setFluctuationRate(item.get("fltRt"));
        dto.setTradingQuantity(item.get("trqu"));
        dto.setTradingPrice(item.get("trP"));
        return dto;
    }
}
