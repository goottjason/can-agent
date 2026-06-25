package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.service.dto.DartApiResponse;
import com.canagent.service.dto.DartCompanyDTO;
import com.canagent.service.dto.DartFinancialDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;

@Service
public class DartApiClient {

    private static final Logger log = LoggerFactory.getLogger(DartApiClient.class);

    private final RestTemplate restTemplate;
    private final ApiConfig apiConfig;

    public DartApiClient(RestTemplate restTemplate, ApiConfig apiConfig) {
        this.restTemplate = restTemplate;
        this.apiConfig = apiConfig;
    }

    public List<DartFinancialDTO> getFinancialStatements(String stockCode, String year, String quarter) {
        String reportCode = getReportCode(quarter);
        String url = UriComponentsBuilder.fromHttpUrl(apiConfig.getDart().getBaseUrl() + "/fnlttMultiAcnt.json")
                .queryParam("crtfc_key", apiConfig.getDart().getKey())
                .queryParam("stock_code", stockCode)
                .queryParam("bsns_year", year)
                .queryParam("reprt_code", reportCode)
                .queryParam("fs_div", "CFS")
                .toUriString();

        try {
            DartApiResponse<DartFinancialDTO> response = restTemplate.getForObject(
                    url, DartApiResponse.class);

            if (response != null && response.isSuccess()) {
                return response.getList();
            }
            log.warn("DART API 응답 실패: {}", response != null ? response.getMessage() : "null");
        } catch (Exception e) {
            log.error("DART API 호출 실패: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    public String getCorporationName(String stockCode) {
        String url = UriComponentsBuilder.fromHttpUrl(apiConfig.getDart().getBaseUrl() + "/company.json")
                .queryParam("crtfc_key", apiConfig.getDart().getKey())
                .queryParam("stock_code", stockCode)
                .toUriString();

        try {
            DartCompanyDTO response = restTemplate.getForObject(url, DartCompanyDTO.class);
            if (response != null && "000".equals(response.getStatus())) {
                return response.getCorpName();
            }
        } catch (Exception e) {
            log.error("DART 기업명 조회 실패: {}", e.getMessage());
        }

        return null;
    }

    private String getReportCode(String quarter) {
        return switch (quarter) {
            case "1" -> "11011";
            case "2" -> "11012";
            case "3" -> "11013";
            case "4" -> "11014";
            default -> "11011";
        };
    }
}
