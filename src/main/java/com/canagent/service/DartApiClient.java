package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.service.dto.DartApiResponse;
import com.canagent.service.dto.DartFinancialDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DartApiClient {

    private static final Logger log = LoggerFactory.getLogger(DartApiClient.class);

    private final RestTemplate restTemplate;
    private final ApiConfig apiConfig;
    private Map<String, String> stockToCorpCode = new HashMap<>();

    public DartApiClient(RestTemplate restTemplate, ApiConfig apiConfig) {
        this.restTemplate = restTemplate;
        this.apiConfig = apiConfig;
    }

    private synchronized String getCorpCode(String stockCode) {
        if (stockToCorpCode.isEmpty()) {
            loadCorpCodes();
        }
        return stockToCorpCode.get(stockCode);
    }

    private void loadCorpCodes() {
        try {
            ClassPathResource resource = new ClassPathResource("corp_code_map.json");
            ObjectMapper mapper = new ObjectMapper();
            stockToCorpCode = mapper.readValue(resource.getInputStream(),
                    new TypeReference<Map<String, String>>() {});
            log.info("DART corp_code 로드 완료: {}건", stockToCorpCode.size());
        } catch (Exception e) {
            log.error("DART corp_code 로드 실패: {}", e.getMessage());
        }
    }

    public List<DartFinancialDTO> getFinancialStatements(String stockCode, String year, String quarter) {
        String corpCode = getCorpCode(stockCode);
        if (corpCode == null) {
            log.warn("DART corp_code 없음: {}", stockCode);
            return Collections.emptyList();
        }

        String reportCode = getReportCode(quarter);
        String url = "https://opendart.fss.or.kr/api/fnlttMultiAcnt.json"
                + "?crtfc_key=" + apiConfig.getDart().getKey()
                + "&corp_code=" + corpCode
                + "&bsns_year=" + year
                + "&reprt_code=" + reportCode
                + "&fs_div=CFS";

        try {
            DartApiResponse<DartFinancialDTO> response = restTemplate.getForObject(
                    url, DartApiResponse.class);

            if (response != null && response.isSuccess()) {
                return response.getList();
            }
            log.warn("DART API 응답 실패: {} - {}", stockCode, response != null ? response.getMessage() : "null");
        } catch (Exception e) {
            log.error("DART API 호출 실패: {} - {}", stockCode, e.getMessage());
        }

        return Collections.emptyList();
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
