package com.canagent.worker;

import com.canagent.worker.IntradayMonitorWorker.PlanInput;
import com.canagent.worker.IntradayMonitorWorker.PlanResult;
import com.canagent.worker.IntradayMonitorWorker.PlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("매수 계획(planPurchases) notional 순수 로직 단위테스트 — 총자산 기준 상한·잔여 미배분(dry powder)")
class IntradayMonitorWorkerPlanTest {

    private Map<String, PlanResult> byCode(List<PlanResult> results) {
        return results.stream().collect(Collectors.toMap(PlanResult::code, r -> r));
    }

    private BigDecimal totalAmount(List<PlanResult> results) {
        return results.stream().map(PlanResult::orderAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PlanInput in(String code, String price, int score) {
        return new PlanInput(code, new BigDecimal(price), score);
    }

    // 새 시그니처: planPurchases(signals, totalEquity, availableCash, availableSlots, positionRate)
    // perPositionCap = totalEquity × positionRate/100, 실제 배분 = min(perPositionCap, 잔여 가용현금).

    @Test
    @DisplayName("notional: 고가 종목도 소수 매수 가능 — 종목당 상한 이내 배분")
    void highPriceStock_isBoughtFractionally() {
        List<PlanInput> signals = List.of(
                in("000660", "2263000", 141),
                in("126640", "3600", 154),
                in("001200", "4215", 144)
        );

        // 총자산=가용현금=100만, positionRate=5 → 종목당 상한 5만
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(
                signals, new BigDecimal("1000000"), new BigDecimal("1000000"), 10, 5);
        Map<String, PlanResult> m = byCode(plan);

        assertThat(m.get("000660").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("000660").orderAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(m.get("126640").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("001200").status()).isEqualTo(PlanStatus.BUY);
        // 각 종목 배분은 종목당 상한(5만) 이내
        assertThat(m.get("000660").orderAmount()).isLessThanOrEqualTo(new BigDecimal("50000"));
        assertThat(m.get("126640").orderAmount()).isLessThanOrEqualTo(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("총 배분금액은 가용현금을 절대 초과하지 않는다")
    void neverExceedsAvailableCash() {
        List<PlanInput> signals = List.of(
                in("A", "3600", 150),
                in("B", "9580", 144),
                in("C", "11230", 144),
                in("D", "23850", 140),
                in("E", "41750", 138)
        );
        BigDecimal cash = new BigDecimal("1000000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, cash, 10, 5);
        assertThat(totalAmount(plan)).isLessThanOrEqualTo(cash);
    }

    @Test
    @DisplayName("슬롯 초과분은 LIMIT_REACHED (점수 낮은 종목이 밀림)")
    void excessOverSlots_isLimitReached() {
        List<PlanInput> signals = List.of(
                in("HI", "5000", 160),
                in("MID", "5000", 150),
                in("LO", "5000", 140)
        );
        BigDecimal cash = new BigDecimal("1000000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, cash, 2, 5);
        Map<String, PlanResult> m = byCode(plan);

        assertThat(m.get("HI").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("MID").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("LO").status()).isEqualTo(PlanStatus.LIMIT_REACHED);
    }

    @Test
    @DisplayName("상한만 배분하고 잔여 현금은 미배분(dry powder)로 보유 — reflow 제거")
    void capsPerPositionAndKeepsLeftoverAsDryPowder() {
        List<PlanInput> signals = List.of(
                in("A", "3600", 150),
                in("B", "9450", 148),
                in("C", "11230", 144),
                in("D", "143500", 142)
        );
        BigDecimal cash = new BigDecimal("1000000");
        // positionRate=5 → 종목당 상한 5만. 4종목 × 5만 = 20만만 배분, 80만은 미배분.
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, cash, 10, 5);

        long bought = plan.stream().filter(r -> r.status() == PlanStatus.BUY).count();
        assertThat(bought).isEqualTo(4); // 4종목 모두 배분(분산)

        // 각 종목 정확히 상한(5만)만 배분
        Map<String, PlanResult> m = byCode(plan);
        assertThat(m.get("A").orderAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(m.get("D").orderAmount()).isEqualByComparingTo(new BigDecimal("50000"));

        // 총 배분 = 20만, 잔여 80만은 미배분(dry powder)
        assertThat(totalAmount(plan)).isEqualByComparingTo(new BigDecimal("200000"));
        assertThat(totalAmount(plan)).isLessThan(cash); // 잔여 현금 존재
    }

    @Test
    @DisplayName("가용 슬롯 0이면 전량 LIMIT_REACHED, 배분 0")
    void noSlots_allLimitReached() {
        List<PlanInput> signals = List.of(
                in("A", "3600", 150),
                in("B", "9580", 144)
        );
        BigDecimal cash = new BigDecimal("1000000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, cash, 0, 5);
        assertThat(plan).allMatch(r -> r.status() == PlanStatus.LIMIT_REACHED);
        assertThat(totalAmount(plan)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("현재가 0 이하는 제외(소수 수량 환산 불가), 정상 종목만 배분")
    void invalidPrice_isExcluded() {
        List<PlanInput> signals = List.of(
                in("BAD", "0", 200),
                in("NEG", "-100", 190),
                in("OK", "3600", 150)
        );
        BigDecimal cash = new BigDecimal("1000000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, cash, 10, 5);
        Map<String, PlanResult> m = byCode(plan);
        assertThat(m.get("BAD").status()).isEqualTo(PlanStatus.MIN_AMOUNT);
        assertThat(m.get("NEG").status()).isEqualTo(PlanStatus.MIN_AMOUNT);
        assertThat(m.get("OK").status()).isEqualTo(PlanStatus.BUY);
        assertThat(totalAmount(plan)).isLessThanOrEqualTo(cash);
    }

    @Test
    @DisplayName("positionRate 0이면 종목당 상한 0 → 배분 0(MIN_AMOUNT), 현금 전액 보유")
    void zeroPositionRate_allocatesNothing() {
        List<PlanInput> signals = List.of(in("A", "3600", 150));
        BigDecimal cash = new BigDecimal("100000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, cash, 10, 0);
        assertThat(byCode(plan).get("A").status()).isEqualTo(PlanStatus.MIN_AMOUNT);
        assertThat(totalAmount(plan)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("사용자 시나리오: 총자산 100만원 혼합 종목 — 종목당 상한(5%=5만)까지만 배분, 잔여 보유")
    void userScenario_oneMillionCash() {
        List<PlanInput> signals = List.of(
                in("126640", "3600", 154),
                in("001200", "4215", 144),
                in("091590", "9580", 144),
                in("031330", "11230", 144),
                in("025560", "41750", 144),
                in("095610", "143500", 144),
                in("000660", "2263000", 141),
                in("402340", "1380000", 144)
        );
        BigDecimal cash = new BigDecimal("1000000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, cash, 20, 5);

        long bought = plan.stream().filter(r -> r.status() == PlanStatus.BUY).count();
        assertThat(bought).isEqualTo(8);
        // 8종목 × 5만 상한 = 40만 배분, 60만 미배분(dry powder)
        assertThat(totalAmount(plan)).isEqualByComparingTo(new BigDecimal("400000"));
        assertThat(totalAmount(plan)).isLessThan(cash);
    }

    @Test
    @DisplayName("첫날 버그 재발 방지: 2신호·소액 시드(총자산 10만·현금 10만·rate 5) → 각 5%(5천)만 배분, 나머지 9만 미배분")
    void smallSeedTwoSignals_capsAtFivePercentEach_keepsRest() {
        List<PlanInput> signals = List.of(
                in("SIG1", "3600", 150),
                in("SIG2", "4215", 148)
        );
        BigDecimal cash = new BigDecimal("100000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, cash, 20, 5);
        Map<String, PlanResult> m = byCode(plan);

        // 각 종목 정확히 5% = 5,000만 배분 (50/50 전액소진 아님)
        assertThat(m.get("SIG1").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("SIG1").orderAmount()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(m.get("SIG2").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("SIG2").orderAmount()).isEqualByComparingTo(new BigDecimal("5000"));

        // 총 1만 배분, 9만은 미배분(현금 보유) — 다음 신호가 매수 가능
        assertThat(totalAmount(plan)).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("총자산 > 가용현금(기존 보유 존재): 상한은 총자산 기준, 배분은 현금 한도 내")
    void capUsesEquityButAllocationBoundedByCash() {
        List<PlanInput> signals = List.of(
                in("A", "3600", 150),
                in("B", "4215", 148)
        );
        // 총자산 100만(보유평가 포함)이지만 가용현금은 3만뿐.
        // 종목당 상한 = 100만 × 5% = 5만. 그러나 현금 3만이 한도.
        BigDecimal equity = new BigDecimal("1000000");
        BigDecimal cash = new BigDecimal("30000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, equity, cash, 20, 5);
        Map<String, PlanResult> m = byCode(plan);

        // A는 상한 5만 원하지만 현금 3만이 한도 → 3만 배분(min).
        assertThat(m.get("A").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("A").orderAmount()).isEqualByComparingTo(new BigDecimal("30000"));
        // B는 현금 소진 후 배분 0 → MIN_AMOUNT.
        assertThat(m.get("B").status()).isEqualTo(PlanStatus.MIN_AMOUNT);
        // 총 배분은 가용현금(3만) 초과 안 함.
        assertThat(totalAmount(plan)).isEqualByComparingTo(new BigDecimal("30000"));
    }
}
