package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.Win720Draw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("연금복권 당첨 파서 단위테스트")
class Win720ParserTest {

    @Test
    @DisplayName("결과 HTML에서 조·6자리·보너스를 추출한다")
    void parse() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/fixtures/win720-round240.html"));

        Win720Draw d = Win720Parser.parse(240, html);

        assertThat(d.success()).isTrue();
        assertThat(d.jo()).isEqualTo(3);
        assertThat(d.digits()).isEqualTo("123456");
        assertThat(d.bonusDigits()).isEqualTo("987654");
    }

    @Test
    @DisplayName("빈 HTML이면 success=false, jo=0, digits=''를 반환한다")
    void parse_blankHtml_returnsFailure() {
        Win720Draw d = Win720Parser.parse(240, "");

        assertThat(d.success()).isFalse();
        assertThat(d.jo()).isEqualTo(0);
        assertThat(d.digits()).isEmpty();
    }

    @Test
    @DisplayName("조 번호가 범위(1~5) 밖이면 success=false를 반환한다")
    void parse_joOutOfRange_returnsFailure() {
        String html = "<html><body>" +
                "<div class=\"win720_number\">" +
                "<span class=\"jo\">6조</span>" +
                "<span class=\"num\">1</span><span class=\"num\">2</span><span class=\"num\">3</span>" +
                "<span class=\"num\">4</span><span class=\"num\">5</span><span class=\"num\">6</span>" +
                "</div>" +
                "<div class=\"bonus_number\">" +
                "<span class=\"num\">9</span><span class=\"num\">8</span><span class=\"num\">7</span>" +
                "<span class=\"num\">6</span><span class=\"num\">5</span><span class=\"num\">4</span>" +
                "</div>" +
                "</body></html>";

        Win720Draw d = Win720Parser.parse(240, html);

        assertThat(d.success()).isFalse();
        assertThat(d.jo()).isEqualTo(6);
    }

    @Test
    @DisplayName("win720_number 블록에 span이 6개 미만이면 success=false이고 bonus_number로 유출되지 않는다")
    void parse_fewerThanSixDigits_doesNotBleedIntoBonus() {
        // win720_number에 숫자 3개만, bonus_number에 정상 6개 — 경계 보정 없으면 유출돼 digits=6이 됨
        String html = "<html><body>" +
                "<div class=\"win720_number\">" +
                "<span class=\"jo\">2조</span>" +
                "<span class=\"num\">1</span><span class=\"num\">2</span><span class=\"num\">3</span>" +
                "</div>" +
                "<div class=\"bonus_number\">" +
                "<span class=\"num\">9</span><span class=\"num\">8</span><span class=\"num\">7</span>" +
                "<span class=\"num\">6</span><span class=\"num\">5</span><span class=\"num\">4</span>" +
                "</div>" +
                "</body></html>";

        Win720Draw d = Win720Parser.parse(240, html);

        assertThat(d.success()).isFalse();           // digits 3자리 → success=false
        assertThat(d.digits()).isEqualTo("123");     // bonus_number로 유출 없음
    }
}
