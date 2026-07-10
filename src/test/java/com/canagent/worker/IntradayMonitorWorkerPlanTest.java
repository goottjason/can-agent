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

@DisplayName("매수 계획(planPurchases) notional 순수 로직 단위테스트")
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

    @Test
    @DisplayName("notional: 고가 종목도 소수 매수 가능 — 1주 초과 개념 없음(UNAFFORDABLE 소멸)")
    void highPriceStock_isBoughtFractionally() {
        List<PlanInput> signals = List.of(
                in("000660", "2263000", 141), // 예전엔 1주>예수금이라 UNAFFORDABLE
                in("126640", "3600", 154),
                in("001200", "4215", 144)
        );

        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, new BigDecimal("1000000"), 10, 10);
        Map<String, PlanResult> m = byCode(plan);

        // 소수 매수라 고가주도 배분금액을 받는다(BUY)
        assertThat(m.get("000660").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("000660").orderAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(m.get("126640").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("001200").status()).isEqualTo(PlanStatus.BUY);
    }

    @Test
    @DisplayName("총 배분금액은 예수금을 절대 초과하지 않는다")
    void neverExceedsAvailableCash() {
        List<PlanInput> signals = List.of(
                in("A", "3600", 150),
                in("B", "9580", 144),
                in("C", "11230", 144),
                in("D", "23850", 140),
                in("E", "41750", 138)
        );
        BigDecimal cash = new BigDecimal("1000000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, 10, 10);
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
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, new BigDecimal("1000000"), 2, 10);
        Map<String, PlanResult> m = byCode(plan);

        assertThat(m.get("HI").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("MID").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("LO").status()).isEqualTo(PlanStatus.LIMIT_REACHED);
    }

    @Test
    @DisplayName("분산: 여러 종목에 걸쳐 배분되고 잔여현금이 이월되어 예수금 대부분 소진")
    void diversifiesAndReflowsLeftover() {
        List<PlanInput> signals = List.of(
                in("A", "3600", 150),
                in("B", "9450", 148),
                in("C", "11230", 144),
                in("D", "143500", 142)
        );
        BigDecimal cash = new BigDecimal("1000000");
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, 10, 10);

        long bought = plan.stream().filter(r -> r.status() == PlanStatus.BUY).count();
        assertThat(bought).isEqualTo(4); // 4종목 모두 배분(분산)
        // 이월로 예수금 대부분 소진(잔돈만 남음)
        assertThat(totalAmount(plan)).isLessThanOrEqualTo(cash);
        assertThat(totalAmount(plan)).isGreaterThan(cash.subtract(new BigDecimal("1")));
    }

    @Test
    @DisplayName("가용 슬롯 0이면 전량 LIMIT_REACHED, 배분 0")
    void noSlots_allLimitReached() {
        List<PlanInput> signals = List.of(
                in("A", "3600", 150),
                in("B", "9580", 144)
        );
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, new BigDecimal("1000000"), 0, 10);
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
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, new BigDecimal("1000000"), 10, 10);
        Map<String, PlanResult> m = byCode(plan);
        assertThat(m.get("BAD").status()).isEqualTo(PlanStatus.MIN_AMOUNT);
        assertThat(m.get("NEG").status()).isEqualTo(PlanStatus.MIN_AMOUNT);
        assertThat(m.get("OK").status()).isEqualTo(PlanStatus.BUY);
        assertThat(totalAmount(plan)).isLessThanOrEqualTo(new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("positionRate 0이어도 이월(잔여 소진)로 배분은 진행")
    void zeroPositionRate_stillAllocatesViaReflow() {
        List<PlanInput> signals = List.of(in("A", "3600", 150));
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, new BigDecimal("100000"), 10, 0);
        assertThat(byCode(plan).get("A").status()).isEqualTo(PlanStatus.BUY);
        assertThat(totalAmount(plan)).isLessThanOrEqualTo(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("사용자 시나리오: 예수금 100만원 혼합 종목 — 전부 소수 매수(분산)")
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
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, 10, 10);

        // 소수 매수라 고가주도 배분됨(예전 UNAFFORDABLE이 사라짐)
        long bought = plan.stream().filter(r -> r.status() == PlanStatus.BUY).count();
        assertThat(bought).isEqualTo(8);
        assertThat(totalAmount(plan)).isLessThanOrEqualTo(cash);
    }
}
