package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class LottoResultClient {

    private static final Logger log = LoggerFactory.getLogger(LottoResultClient.class);
    private static final String URL =
            "https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public LottoResultClient(RestTemplate restTemplate, ObjectMapper mapper) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    public LottoDraw getWinningNumbers(int roundNo) {
        try {
            String body = restTemplate.getForObject(URL + roundNo, String.class);
            return parse(body);
        } catch (Exception e) {
            log.error("로또 당첨 조회 실패 (회차 {}): {}", roundNo, e.getMessage());
            return new LottoDraw(0, List.of(), 0, 0, false);
        }
    }

    LottoDraw parse(String json) {
        if (json == null) return new LottoDraw(0, List.of(), 0, 0, false);
        try {
            JsonNode n = mapper.readTree(json);
            boolean success = "success".equals(n.path("returnValue").asText());
            if (!success) {
                return new LottoDraw(0, List.of(), 0, 0, false);
            }
            List<Integer> nums = List.of(
                    n.path("drwtNo1").asInt(), n.path("drwtNo2").asInt(), n.path("drwtNo3").asInt(),
                    n.path("drwtNo4").asInt(), n.path("drwtNo5").asInt(), n.path("drwtNo6").asInt());
            return new LottoDraw(n.path("drwNo").asInt(), nums, n.path("bnusNo").asInt(),
                    n.path("firstWinamnt").asLong(), true);
        } catch (Exception e) {
            log.error("로또 당첨 파싱 실패: {}", e.getMessage());
            return new LottoDraw(0, List.of(), 0, 0, false);
        }
    }
}
