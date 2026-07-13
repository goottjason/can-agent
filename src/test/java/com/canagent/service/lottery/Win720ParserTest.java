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
}
