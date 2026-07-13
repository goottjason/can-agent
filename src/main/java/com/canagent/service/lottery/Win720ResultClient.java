package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.Win720Draw;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class Win720ResultClient {

    private static final String URL =
            "https://www.dhlottery.co.kr/gameResult.do?method=win720&Round=";

    private final RestTemplate restTemplate;

    public Win720ResultClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Win720Draw getWinningNumbers(int roundNo) {
        String html = restTemplate.getForObject(URL + roundNo, String.class);
        return Win720Parser.parse(roundNo, html);
    }
}
