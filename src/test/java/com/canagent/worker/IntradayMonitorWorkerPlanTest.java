package com.canagent.worker;

import com.canagent.worker.IntradayMonitorWorker.PlanInput;
import com.canagent.worker.IntradayMonitorWorker.PlanResult;
import com.canagent.worker.IntradayMonitorWorker.PlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("매수 계획(planPurchases) 순수 로직 단위테스트")
class IntradayMonitorWorkerPlanTest {

    private Map<String, PlanResult> byCode(List<PlanResult> results) {
        return results.stream().collect(Collectors.toMap(PlanResult::code, r -> r));
    }

    private long totalCost(List<PlanResult> results) {
        return results.stream().mapToLong(PlanResult::cost).sum();
    }

    @Test
    @DisplayName("예수금 필터: 1주 가격이 예수금 초과면 UNAFFORDABLE (슬롯 미소비)")
    void filtersUnaffordable_withoutConsumingSlot() {
        List<PlanInput> signals = List.of(
                new PlanInput("000660", 2_263_000, 141), // SK하이닉스 1주 > 예수금
                new PlanInput("126640", 3_600, 154),     // 화신정공
                new PlanInput("001200", 4_215, 144)      // 유진투자증권
        );

        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, 1_000_000, 10, 10);
        Map<String, PlanResult> m = byCode(plan);

        assertThat(m.get("000660").status()).isEqualTo(PlanStatus.UNAFFORDABLE);
        assertThat(m.get("000660").qty()).isZero();
        // 슬롯 미소비 → 나머지 저가주는 정상 매수
        assertThat(m.get("126640").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("001200").status()).isEqualTo(PlanStatus.BUY);
    }

    @Test
    @DisplayName("총 매수금액은 예수금을 절대 초과하지 않는다")
    void neverExceedsAvailableCash() {
        List<PlanInput> signals = List.of(
                new PlanInput("A", 3_600, 150),
                new PlanInput("B", 9_580, 144),
                new PlanInput("C", 11_230, 144),
                new PlanInput("D", 23_850, 140),
                new PlanInput("E", 41_750, 138)
        );
        long cash = 1_000_000;
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, 10, 10);
        assertThat(totalCost(plan)).isLessThanOrEqualTo(cash);
    }

    @Test
    @DisplayName("슬롯 초과분은 LIMIT_REACHED (점수 낮은 종목이 밀림)")
    void excessOverSlots_isLimitReached() {
        List<PlanInput> signals = List.of(
                new PlanInput("HI", 5_000, 160),
                new PlanInput("MID", 5_000, 150),
                new PlanInput("LO", 5_000, 140)
        );
        // 슬롯 2개 → 점수 낮은 LO는 한도 도달
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, 1_000_000, 2, 10);
        Map<String, PlanResult> m = byCode(plan);

        assertThat(m.get("HI").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("MID").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("LO").status()).isEqualTo(PlanStatus.LIMIT_REACHED);
    }

    @Test
    @DisplayName("분산: 여러 종목에 걸쳐 매수되고, 잔여현금이 이월된다")
    void diversifiesAndReflowsLeftover() {
        // 상한 10%(=100k) 안에서 1패스, 이후 잔여를 라운드로빈 이월
        List<PlanInput> signals = List.of(
                new PlanInput("A", 3_600, 150),
                new PlanInput("B", 9_450, 148),
                new PlanInput("C", 11_230, 144),
                new PlanInput("D", 143_500, 142) // 1패스(상한10%=100k)로는 0주, 이월로 매수 가능
        );
        long cash = 1_000_000;
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, 10, 10);
        Map<String, PlanResult> m = byCode(plan);

        // 최소 3종목 이상 매수(분산)
        long boughtNames = plan.stream().filter(r -> r.status() == PlanStatus.BUY).count();
        assertThat(boughtNames).isGreaterThanOrEqualTo(3);
        // 고가(143,500)도 이월로 최소 1주 매수됨
        assertThat(m.get("D").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("D").qty()).isGreaterThanOrEqualTo(1);
        // 예수금 초과 없음
        assertThat(totalCost(plan)).isLessThanOrEqualTo(cash);
    }

    @Test
    @DisplayName("사용자 시나리오: 예수금 100만원 혼합 종목")
    void userScenario_oneMillionCash() {
        List<PlanInput> signals = List.of(
                new PlanInput("126640", 3_600, 154),    // 화신정공
                new PlanInput("001200", 4_215, 144),    // 유진투자증권
                new PlanInput("091590", 9_580, 144),    // 남화토건
                new PlanInput("031330", 11_230, 144),   // 에스에이엠티
                new PlanInput("025560", 41_750, 144),   // 미래산업
                new PlanInput("095610", 143_500, 144),  // 테스
                new PlanInput("000660", 2_263_000, 141),// SK하이닉스 (예산 초과)
                new PlanInput("402340", 1_380_000, 144) // SK스퀘어 (예산 초과)
        );
        long cash = 1_000_000;
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, cash, 10, 10);
        Map<String, PlanResult> m = byCode(plan);

        // 고가 2종목은 예산 초과
        assertThat(m.get("000660").status()).isEqualTo(PlanStatus.UNAFFORDABLE);
        assertThat(m.get("402340").status()).isEqualTo(PlanStatus.UNAFFORDABLE);
        // 살 수 있는 종목들은 매수됨(분산)
        assertThat(m.get("126640").status()).isEqualTo(PlanStatus.BUY);
        assertThat(m.get("095610").status()).isEqualTo(PlanStatus.BUY); // 테스도 이월로 매수
        long boughtNames = plan.stream().filter(r -> r.status() == PlanStatus.BUY).count();
        assertThat(boughtNames).isGreaterThanOrEqualTo(5);
        assertThat(totalCost(plan)).isLessThanOrEqualTo(cash);
    }

    @Test
    @DisplayName("가용 슬롯 0이면 전량 LIMIT_REACHED, 매수 0")
    void noSlots_allLimitReached() {
        List<PlanInput> signals = List.of(
                new PlanInput("A", 3_600, 150),
                new PlanInput("B", 9_580, 144)
        );
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, 1_000_000, 0, 10);
        assertThat(plan).allMatch(r -> r.status() == PlanStatus.LIMIT_REACHED);
        assertThat(totalCost(plan)).isZero();
    }

    @Test
    @DisplayName("현재가 0 이하는 제외(0으로 나눔 방지), 정상 종목만 매수")
    void invalidPrice_isExcluded() {
        List<PlanInput> signals = List.of(
                new PlanInput("BAD", 0, 200),
                new PlanInput("NEG", -100, 190),
                new PlanInput("OK", 3_600, 150)
        );
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, 1_000_000, 10, 10);
        Map<String, PlanResult> m = byCode(plan);
        assertThat(m.get("BAD").status()).isEqualTo(PlanStatus.MIN_AMOUNT);
        assertThat(m.get("NEG").status()).isEqualTo(PlanStatus.MIN_AMOUNT);
        assertThat(m.get("OK").status()).isEqualTo(PlanStatus.BUY);
        assertThat(totalCost(plan)).isLessThanOrEqualTo(1_000_000);
    }

    @Test
    @DisplayName("positionRate 0이어도 상한 최소 1원 방어로 이월 매수는 진행")
    void zeroPositionRate_stillBuysViaReflow() {
        List<PlanInput> signals = List.of(new PlanInput("A", 3_600, 150));
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, 100_000, 10, 0);
        // 상한이 사실상 0이라 1패스는 0주지만, 이월(라운드로빈)로 매수됨
        assertThat(byCode(plan).get("A").status()).isEqualTo(PlanStatus.BUY);
        assertThat(totalCost(plan)).isLessThanOrEqualTo(100_000);
    }

    @Test
    @DisplayName("모든 종목이 예수금 초과면 매수 0")
    void allUnaffordable_buysNothing() {
        List<PlanInput> signals = List.of(
                new PlanInput("X", 2_000_000, 150),
                new PlanInput("Y", 3_000_000, 140)
        );
        List<PlanResult> plan = IntradayMonitorWorker.planPurchases(signals, 1_000_000, 10, 10);
        assertThat(plan).allMatch(r -> r.status() == PlanStatus.UNAFFORDABLE);
        assertThat(totalCost(plan)).isZero();
    }
}
