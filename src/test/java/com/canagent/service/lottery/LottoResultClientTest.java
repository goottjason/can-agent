package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("로또 당첨 API 파싱 단위테스트")
class LottoResultClientTest {

    private final LottoResultClient client = new LottoResultClient(new RestTemplate(), new ObjectMapper());

    @Test
    @DisplayName("성공 응답을 LottoDraw로 파싱한다 (int 범위 초과 firstWinamnt 포함)")
    void parseSuccess() {
        String json = "{\"returnValue\":\"success\",\"drwNo\":1100,\"drwtNo1\":3,\"drwtNo2\":7,"
                + "\"drwtNo3\":12,\"drwtNo4\":25,\"drwtNo5\":33,\"drwtNo6\":41,\"bnusNo\":10,"
                + "\"firstWinamnt\":3000000000}";

        LottoDraw d = client.parse(json);

        assertThat(d.success()).isTrue();
        assertThat(d.roundNo()).isEqualTo(1100);
        assertThat(d.numbers()).containsExactly(3, 7, 12, 25, 33, 41);
        assertThat(d.bonus()).isEqualTo(10);
        assertThat(d.firstWinAmount()).isEqualTo(3000000000L);
    }

    @Test
    @DisplayName("미추첨(fail) 응답은 모든 센티넬 값을 반환한다")
    void parseFail() {
        LottoDraw d = client.parse("{\"returnValue\":\"fail\"}");
        assertThat(d.success()).isFalse();
        assertThat(d.roundNo()).isEqualTo(0);
        assertThat(d.bonus()).isEqualTo(0);
        assertThat(d.firstWinAmount()).isEqualTo(0L);
        assertThat(d.numbers()).isEmpty();
    }

    @Test
    @DisplayName("파싱 불가 JSON은 success=false 센티넬 반환")
    void parseMalformedJson() {
        LottoDraw d = client.parse("not json{{");
        assertThat(d.success()).isFalse();
    }
}
