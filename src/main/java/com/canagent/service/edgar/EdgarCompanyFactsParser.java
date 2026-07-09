package com.canagent.service.edgar;

import com.canagent.domain.stock.FiscalPeriodType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SEC EDGAR companyfacts JSON → {@link ParsedFinancials} 매핑기.
 *
 * <p>Spring 의존이 없는 순수 파서(단위테스트 용이). 어댑터가 이 결과를 FinancialStatement로 upsert한다.
 *
 * <h3>기간 판정 — 값의 실제 기간(start/end)으로 결정</h3>
 * companyfacts의 {@code fy}/{@code fp}는 <b>공시(filing)의 회계기간</b>이지 값 자체의 기간이 아니다
 * (10-K에 전년 비교치가 fy=당해로 실려온다). 따라서 각 fact의 {@code start}/{@code end} 지속기간으로
 * 판정한다.
 * <ul>
 *   <li>지속 ~1년(300~400일) → {@code ANNUAL}, fiscalYear=end.year, fiscalQuarter=null</li>
 *   <li>지속 ~1분기(80~100일) → {@code QUARTER}, fiscalYear=end.year,
 *       fiscalQuarter=end월 기준 캘린더 분기(1~4)</li>
 *   <li>그 외(반기 YTD·9개월 YTD 등)는 <b>버려</b> standalone 분기값만 남긴다(적재규약①).</li>
 * </ul>
 *
 * <h3>한계(보고서에 명시)</h3>
 * <ul>
 *   <li>비(非)캘린더 회계연도(예: 9월 결산) 종목은 fiscalQuarter가 <b>캘린더 분기 번호</b>로 라벨링된다.
 *       QuarterlyEarningsAnalyzer는 (year-1, 같은 quarter)를 비교하므로 라벨이 <b>연도 간 일관</b>되어
 *       YoY는 정상 동작한다(분기 번호가 SEC 회계 분기와 다를 수 있을 뿐).</li>
 *   <li>Q4 standalone(연간 − Q1..Q3) 도출은 이번 범위 밖(annual 행으로만 커버). Q1~Q3 YoY는 동작.</li>
 *   <li>roe는 <b>연간 행에만</b> 계산 적재(순이익/자본×100). 분기 순이익/자본은 비연율화라 왜곡되므로
 *       null로 둔다(IndustryLeaderAnalyzer는 최신 non-null roe=연간값을 사용).</li>
 * </ul>
 */
@Component
public class EdgarCompanyFactsParser {

    private static final Logger log = LoggerFactory.getLogger(EdgarCompanyFactsParser.class);

    // 매출: Revenues 우선, 없으면 계약매출 태그
    private static final String[] REVENUE_TAGS = {
            "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax"
    };
    private static final String OPERATING_INCOME_TAG = "OperatingIncomeLoss";
    private static final String NET_INCOME_TAG = "NetIncomeLoss";
    // EPS: 희석 우선, 없으면 기본
    private static final String[] EPS_TAGS = {
            "EarningsPerShareDiluted", "EarningsPerShareBasic"
    };
    private static final String EQUITY_TAG = "StockholdersEquity";
    private static final String LIABILITIES_TAG = "Liabilities";

    private static final int ANNUAL_MIN_DAYS = 300;
    private static final int ANNUAL_MAX_DAYS = 400;
    private static final int QUARTER_MIN_DAYS = 80;
    private static final int QUARTER_MAX_DAYS = 100;

    public List<ParsedFinancials> parse(JsonNode companyFacts) {
        if (companyFacts == null) return List.of();
        JsonNode usgaap = companyFacts.path("facts").path("us-gaap");
        if (usgaap.isMissingNode() || !usgaap.isObject()) {
            log.warn("EDGAR 파싱: us-gaap facts 없음 (cik={})", companyFacts.path("cik").asText("?"));
            return List.of();
        }

        Map<PeriodKey, Acc> accs = new LinkedHashMap<>();

        collectDuration(usgaap, REVENUE_TAGS, accs, DurationField.REVENUE);
        collectDuration(usgaap, new String[]{OPERATING_INCOME_TAG}, accs, DurationField.OPERATING_INCOME);
        collectDuration(usgaap, new String[]{NET_INCOME_TAG}, accs, DurationField.NET_INCOME);
        collectDuration(usgaap, EPS_TAGS, accs, DurationField.EPS);

        Map<LocalDate, BigDecimal> equityByEnd = collectInstant(usgaap, EQUITY_TAG);
        Map<LocalDate, BigDecimal> liabByEnd = collectInstant(usgaap, LIABILITIES_TAG);

        List<ParsedFinancials> out = new ArrayList<>();
        for (Map.Entry<PeriodKey, Acc> e : accs.entrySet()) {
            PeriodKey pk = e.getKey();
            Acc acc = e.getValue();

            BigDecimal equity = equityByEnd.get(acc.end);
            BigDecimal liabilities = liabByEnd.get(acc.end);

            BigDecimal roe = null;
            if (pk.periodType == FiscalPeriodType.ANNUAL) {
                roe = ratioPercent(acc.netIncome, equity);
            }
            BigDecimal debtRatio = ratioPercent(liabilities, equity);

            ParsedFinancials pf = new ParsedFinancials(
                    pk.fiscalYear, pk.periodType, pk.fiscalQuarter, acc.end,
                    acc.currency != null ? acc.currency : "USD",
                    acc.revenue, acc.operatingIncome, acc.netIncome, acc.eps, roe, debtRatio);
            out.add(pf);
        }
        return out;
    }

    /** {@code value / base * 100}. base가 없거나 0이면 null. 결과 scale 4(HALF_UP). */
    private BigDecimal ratioPercent(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
        return value.divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private enum DurationField { REVENUE, OPERATING_INCOME, NET_INCOME, EPS }

    private void collectDuration(JsonNode usgaap, String[] tags,
                                 Map<PeriodKey, Acc> accs, DurationField field) {
        // 우선순위가 높은 태그(배열 앞)가 이미 채운 기간은 하위 태그로 덮지 않는다.
        Set<PeriodKey> filled = new HashSet<>();
        for (String tag : tags) {
            JsonNode units = usgaap.path(tag).path("units");
            if (units.isMissingNode() || !units.isObject()) continue;

            // 이 태그 내에서 기간별 최적 후보(가장 최근 filed) 선정
            Map<PeriodKey, Cand> best = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> unitIt = units.fields();
            while (unitIt.hasNext()) {
                Map.Entry<String, JsonNode> unitEntry = unitIt.next();
                String unit = unitEntry.getKey();
                for (JsonNode f : unitEntry.getValue()) {
                    Classified c = classifyDuration(f);
                    if (c == null) continue;
                    Cand cand = new Cand(c.val, c.filed, c.end, unit);
                    best.merge(c.key, cand, (a, b) -> b.filed.isAfter(a.filed) ? b : a);
                }
            }

            for (Map.Entry<PeriodKey, Cand> en : best.entrySet()) {
                PeriodKey pk = en.getKey();
                if (filled.contains(pk)) continue;
                Cand cand = en.getValue();
                Acc acc = accs.computeIfAbsent(pk, k -> new Acc());
                acc.end = maxDate(acc.end, cand.end);
                switch (field) {
                    case REVENUE -> { acc.revenue = cand.val; acc.currency = moneyCurrency(acc.currency, cand.unit); }
                    case OPERATING_INCOME -> { acc.operatingIncome = cand.val; acc.currency = moneyCurrency(acc.currency, cand.unit); }
                    case NET_INCOME -> { acc.netIncome = cand.val; acc.currency = moneyCurrency(acc.currency, cand.unit); }
                    case EPS -> acc.eps = cand.val;
                }
                filled.add(pk);
            }
        }
    }

    private Map<LocalDate, BigDecimal> collectInstant(JsonNode usgaap, String tag) {
        Map<LocalDate, BigDecimal> byEnd = new HashMap<>();
        Map<LocalDate, LocalDate> filedByEnd = new HashMap<>();
        JsonNode units = usgaap.path(tag).path("units");
        if (units.isMissingNode() || !units.isObject()) return byEnd;

        Iterator<Map.Entry<String, JsonNode>> unitIt = units.fields();
        while (unitIt.hasNext()) {
            for (JsonNode f : unitIt.next().getValue()) {
                if (f.has("start")) continue; // instant는 start 없음
                LocalDate end = parseDate(f.path("end").asText(null));
                if (end == null || !f.has("val")) continue;
                if (!isFinancialForm(f.path("form").asText(""))) continue;
                LocalDate filed = parseDateOrDefault(f.path("filed").asText(null), end);
                LocalDate prev = filedByEnd.get(end);
                if (prev == null || filed.isAfter(prev)) {
                    byEnd.put(end, new BigDecimal(f.path("val").asText()));
                    filedByEnd.put(end, filed);
                }
            }
        }
        return byEnd;
    }

    /** duration fact를 기간 유형으로 분류. 판정 불가·비대상이면 null. */
    private Classified classifyDuration(JsonNode f) {
        if (!f.has("val")) return null;
        LocalDate end = parseDate(f.path("end").asText(null));
        LocalDate start = parseDate(f.path("start").asText(null));
        if (end == null || start == null) return null;
        if (!isFinancialForm(f.path("form").asText(""))) return null;

        BigDecimal val;
        try {
            val = new BigDecimal(f.path("val").asText());
        } catch (NumberFormatException e) {
            return null;
        }
        LocalDate filed = parseDateOrDefault(f.path("filed").asText(null), end);
        long days = ChronoUnit.DAYS.between(start, end);

        if (days >= ANNUAL_MIN_DAYS && days <= ANNUAL_MAX_DAYS) {
            PeriodKey pk = new PeriodKey(end.getYear(), FiscalPeriodType.ANNUAL, null);
            return new Classified(pk, val, filed, end);
        }
        if (days >= QUARTER_MIN_DAYS && days <= QUARTER_MAX_DAYS) {
            int q = (end.getMonthValue() - 1) / 3 + 1;
            PeriodKey pk = new PeriodKey(end.getYear(), FiscalPeriodType.QUARTER, q);
            return new Classified(pk, val, filed, end);
        }
        return null; // 반기/9개월 YTD 등 → standalone 아님, 버림
    }

    private static boolean isFinancialForm(String form) {
        return form.startsWith("10-K") || form.startsWith("10-Q");
    }

    private static String moneyCurrency(String existing, String unit) {
        if (existing != null) return existing;
        if (unit != null && unit.startsWith("USD") && !unit.contains("/")) return unit;
        return existing;
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseDateOrDefault(String s, LocalDate def) {
        LocalDate d = parseDate(s);
        return d != null ? d : def;
    }

    // --- 내부 캐리어 ---

    private static final class PeriodKey {
        final int fiscalYear;
        final FiscalPeriodType periodType;
        final Integer fiscalQuarter;

        PeriodKey(int fiscalYear, FiscalPeriodType periodType, Integer fiscalQuarter) {
            this.fiscalYear = fiscalYear;
            this.periodType = periodType;
            this.fiscalQuarter = fiscalQuarter;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PeriodKey pk)) return false;
            return fiscalYear == pk.fiscalYear && periodType == pk.periodType
                    && Objects.equals(fiscalQuarter, pk.fiscalQuarter);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fiscalYear, periodType, fiscalQuarter);
        }
    }

    private static final class Acc {
        LocalDate end;
        String currency;
        BigDecimal revenue;
        BigDecimal operatingIncome;
        BigDecimal netIncome;
        BigDecimal eps;
    }

    private static final class Cand {
        final BigDecimal val;
        final LocalDate filed;
        final LocalDate end;
        final String unit;

        Cand(BigDecimal val, LocalDate filed, LocalDate end, String unit) {
            this.val = val;
            this.filed = filed;
            this.end = end;
            this.unit = unit;
        }
    }

    private static final class Classified {
        final PeriodKey key;
        final BigDecimal val;
        final LocalDate filed;
        final LocalDate end;

        Classified(PeriodKey key, BigDecimal val, LocalDate filed, LocalDate end) {
            this.key = key;
            this.val = val;
            this.filed = filed;
            this.end = end;
        }
    }

    /** 파싱 결과 1건(=FinancialStatement 후보). */
    public static final class ParsedFinancials {
        private final int fiscalYear;
        private final FiscalPeriodType periodType;
        private final Integer fiscalQuarter;
        private final LocalDate reportDate;
        private final String currency;
        private final BigDecimal revenue;
        private final BigDecimal operatingIncome;
        private final BigDecimal netIncome;
        private final BigDecimal eps;
        private final BigDecimal roe;
        private final BigDecimal debtRatio;

        public ParsedFinancials(int fiscalYear, FiscalPeriodType periodType, Integer fiscalQuarter,
                                LocalDate reportDate, String currency,
                                BigDecimal revenue, BigDecimal operatingIncome, BigDecimal netIncome,
                                BigDecimal eps, BigDecimal roe, BigDecimal debtRatio) {
            this.fiscalYear = fiscalYear;
            this.periodType = periodType;
            this.fiscalQuarter = fiscalQuarter;
            this.reportDate = reportDate;
            this.currency = currency;
            this.revenue = revenue;
            this.operatingIncome = operatingIncome;
            this.netIncome = netIncome;
            this.eps = eps;
            this.roe = roe;
            this.debtRatio = debtRatio;
        }

        public int getFiscalYear() { return fiscalYear; }
        public FiscalPeriodType getPeriodType() { return periodType; }
        public Integer getFiscalQuarter() { return fiscalQuarter; }
        public LocalDate getReportDate() { return reportDate; }
        public String getCurrency() { return currency; }
        public BigDecimal getRevenue() { return revenue; }
        public BigDecimal getOperatingIncome() { return operatingIncome; }
        public BigDecimal getNetIncome() { return netIncome; }
        public BigDecimal getEps() { return eps; }
        public BigDecimal getRoe() { return roe; }
        public BigDecimal getDebtRatio() { return debtRatio; }
    }
}
