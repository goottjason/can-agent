package com.canagent.service;

import com.canagent.config.ApiConfig;
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
                                    .map(this::mapToDTO)
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

    private KrxPriceDTO mapToDTO(java.util.Map<String, String> item) {
        var dto = new KrxPriceDTO();
        return dto;
    }
}
