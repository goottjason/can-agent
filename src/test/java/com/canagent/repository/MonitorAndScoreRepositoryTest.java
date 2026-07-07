package com.canagent.repository;

import com.canagent.domain.analysis.AnalysisScore;
import com.canagent.domain.analysis.MonitorCheckLog;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("MonitorCheckLog·AnalysisScore 대시보드 공급 쿼리 단위테스트")
class MonitorAndScoreRepositoryTest {

    @Autowired
    private MonitorCheckLogRepository monitorCheckLogRepository;

    @Autowired
    private AnalysisScoreRepository analysisScoreRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("활성 포트폴리오 조회는 Stock을 즉시 로딩한다(매도 경로 LazyInit 방지)")
    void findActiveWithStock_fetchesStock() {
        Stock stock = stockRepository.save(new Stock("000401", "보유주", "KOSPI", "IT"));
        portfolioRepository.save(new Portfolio(stock, new java.math.BigDecimal("10"), new java.math.BigDecimal("70000")));
        // 영속성 컨텍스트 비워 프록시 대신 실제 로딩 여부 검증
        entityManager.flush();
        entityManager.clear();

        List<Portfolio> active = portfolioRepository.findActiveWithStock();

        assertThat(active).hasSize(1);
        // 세션을 닫지 않아도 JOIN FETCH로 이미 초기화되어 있어야 함
        assertThat(active.get(0).getStock().getName()).isEqualTo("보유주");
    }

    @Test
    @DisplayName("가장 최근 검사 로그를 반환하고 오늘 검사 횟수를 센다")
    void latestAndTodayCount() {
        LocalDateTime t1 = LocalDateTime.of(2026, 7, 7, 9, 5);
        LocalDateTime t2 = LocalDateTime.of(2026, 7, 7, 9, 10);
        LocalDateTime yesterday = LocalDateTime.of(2026, 7, 6, 14, 0);
        monitorCheckLogRepository.save(log(t1, 100, 6));
        monitorCheckLogRepository.save(log(t2, 200, 9));
        monitorCheckLogRepository.save(log(yesterday, 50, 1));

        Optional<MonitorCheckLog> latest = monitorCheckLogRepository.findTopByOrderByCheckTimeDesc();
        assertThat(latest).isPresent();
        assertThat(latest.get().getScanned()).isEqualTo(200);

        long todayCount = monitorCheckLogRepository.countByCheckTimeBetween(
                LocalDate.of(2026, 7, 7).atStartOfDay(),
                LocalDate.of(2026, 7, 8).atStartOfDay());
        assertThat(todayCount).isEqualTo(2);
    }

    private MonitorCheckLog log(LocalDateTime checkTime, int scanned, int signalMiss) {
        return new MonitorCheckLog(checkTime, scanned, 1, 2, signalMiss, 3, 4, 1, 2, 120, "[]", "[]");
    }

    @Test
    @DisplayName("Top-N 리더보드는 총점 내림차순으로 Stock을 즉시 로딩해 반환한다")
    void leaderboardOrdersByTotalScoreDesc() {
        LocalDate date = LocalDate.of(2026, 7, 7);
        Stock a = stockRepository.save(new Stock("000301", "저점주", "KOSPI", "IT"));
        Stock b = stockRepository.save(new Stock("000302", "고점주", "KOSPI", "IT"));
        analysisScoreRepository.save(new AnalysisScore(a, date, "AUTO", 55, 0, 0, 10, 15, 8, 0, 45, "NO_PATTERN", 100));
        analysisScoreRepository.save(new AnalysisScore(b, date, "AUTO", 48, 0, 0, 15, 15, 10, 0, 77, "CUP_WITH_HANDLE", 125));

        Optional<LocalDate> latest = analysisScoreRepository.findLatestAnalysisDate();
        assertThat(latest).contains(date);

        List<AnalysisScore> top = analysisScoreRepository.findTopByAnalysisDate(date, PageRequest.of(0, 20));
        assertThat(top).hasSize(2);
        assertThat(top.get(0).getTotalScore()).isEqualTo(125);
        assertThat(top.get(0).getStock().getName()).isEqualTo("고점주");
    }
}
