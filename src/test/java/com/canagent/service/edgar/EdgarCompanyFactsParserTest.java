package com.canagent.service.edgar;

import com.canagent.domain.stock.FiscalPeriodType;
import com.canagent.service.edgar.EdgarCompanyFactsParser.ParsedFinancials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EDGAR companyfacts 파서 단위테스트 (검증 a)")
class EdgarCompanyFactsParserTest {

    private final EdgarCompanyFactsParser parser = new EdgarCompanyFactsParser();
    private List<ParsedFinancials> parsed;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode facts = new ObjectMapper().readTree(
                new ClassPathResource("edgar/companyfacts-sample.json").getInputStream());
        parsed = parser.parse(facts);
    }

    private ParsedFinancials find(int year, FiscalPeriodType type, Integer quarter) {
        return parsed.stream()
                .filter(p -> p.getFiscalYear() == year && p.getPeriodType() == type)
                .filter(p -> quarter == null ? p.getFiscalQuarter() == null
                        : quarter.equals(p.getFiscalQuarter()))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("10-K → ANNUAL(fiscalQuarter=null) 매핑 + revenue/netIncome/eps")
    void annualMapping() {
        ParsedFinancials fy2023 = find(2023, FiscalPeriodType.ANNUAL, null);

        assertThat(fy2023).isNotNull();
        assertThat(fy2023.getFiscalQuarter()).isNull();
        assertThat(fy2023.getPeriodType()).isEqualTo(FiscalPeriodType.ANNUAL);
        assertThat(fy2023.getRevenue()).isEqualByComparingTo("1000");
        assertThat(fy2023.getOperatingIncome()).isEqualByComparingTo("250");
        assertThat(fy2023.getNetIncome()).isEqualByComparingTo("200");
        assertThat(fy2023.getEps()).isEqualByComparingTo("2.00");
        assertThat(fy2023.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("roe = NetIncome/StockholdersEquity×100, debtRatio = Liabilities/Equity×100 (연간 행)")
    void computedRoeAndDebtRatio() {
        ParsedFinancials fy2023 = find(2023, FiscalPeriodType.ANNUAL, null);

        // 200/1000×100 = 20, IndustryLeaderAnalyzer의 %(20/15) 임계값과 정합
        assertThat(fy2023.getRoe()).isEqualByComparingTo("20");
        // 500/1000×100 = 50
        assertThat(fy2023.getDebtRatio()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("10-Q → QUARTER(1~3) 매핑, 분기 roe는 null(연간만 계산)")
    void quarterMapping() {
        ParsedFinancials q1 = find(2024, FiscalPeriodType.QUARTER, 1);

        assertThat(q1).isNotNull();
        assertThat(q1.getFiscalQuarter()).isEqualTo(1);
        assertThat(q1.getRevenue()).isEqualByComparingTo("300");
        assertThat(q1.getNetIncome()).isEqualByComparingTo("60");
        assertThat(q1.getEps()).isEqualByComparingTo("0.60");
        assertThat(q1.getRoe()).isNull();
        // 550/1100×100 = 50
        assertThat(q1.getDebtRatio()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("분기 standalone 정규화: YTD(누적) 값은 버리고 3개월 값만 채택")
    void standaloneQuarterNotYtd() {
        ParsedFinancials q2 = find(2024, FiscalPeriodType.QUARTER, 2);

        assertThat(q2).isNotNull();
        // 누적(1~6월) 650이 아니라 standalone(4~6월) 350
        assertThat(q2.getRevenue()).isEqualByComparingTo("350");
    }

    @Test
    @DisplayName("연간 히스토리 2개년(YoY 가능)과 분기 3건이 매핑된다")
    void periodCounts() {
        long annual = parsed.stream().filter(p -> p.getPeriodType() == FiscalPeriodType.ANNUAL).count();
        long quarter = parsed.stream().filter(p -> p.getPeriodType() == FiscalPeriodType.QUARTER).count();

        assertThat(annual).isEqualTo(2);   // FY2022, FY2023
        assertThat(quarter).isEqualTo(3);  // 2023Q1, 2024Q1, 2024Q2
    }

    @Test
    @DisplayName("us-gaap facts가 없으면 빈 리스트")
    void emptyWhenNoFacts() throws Exception {
        JsonNode empty = new ObjectMapper().readTree("{\"cik\":1,\"facts\":{}}");
        assertThat(parser.parse(empty)).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }
}
