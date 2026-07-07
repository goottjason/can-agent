package com.canagent.domain.analysis;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 장중 모니터링 1회 검사의 퍼널 집계·탈락 사유 요약을 영속화한다.
 * 재시작 후에도 이력이 유지되도록 인스턴스 필드가 아닌 DB에 남긴다. (관측성 R3·R4)
 */
@Entity
@Table(name = "monitor_check_logs")
public class MonitorCheckLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_time", nullable = false)
    private LocalDateTime checkTime;

    // 퍼널 단계별 카운트
    @Column(name = "scanned")
    private int scanned;

    @Column(name = "price_fail_count")
    private int priceFailCount;

    @Column(name = "held_skip_count")
    private int heldSkipCount;

    @Column(name = "signal_miss_count")
    private int signalMissCount;

    @Column(name = "score_miss_count")
    private int scoreMissCount;

    @Column(name = "signal_count")
    private int signalCount;

    @Column(name = "order_success_count")
    private int orderSuccessCount;

    @Column(name = "order_blocked_count")
    private int orderBlockedCount;

    @Column(name = "min_score")
    private int minScore;

    // 상위 미달 종목 요약(JSON) / 신호 실행 결과 요약(JSON)
    @Column(name = "near_miss_json", columnDefinition = "TEXT")
    private String nearMissJson;

    @Column(name = "execution_json", columnDefinition = "TEXT")
    private String executionJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected MonitorCheckLog() {}

    public MonitorCheckLog(LocalDateTime checkTime, int scanned, int priceFailCount,
                           int heldSkipCount, int signalMissCount, int scoreMissCount,
                           int signalCount, int orderSuccessCount, int orderBlockedCount,
                           int minScore, String nearMissJson, String executionJson) {
        this.checkTime = checkTime;
        this.scanned = scanned;
        this.priceFailCount = priceFailCount;
        this.heldSkipCount = heldSkipCount;
        this.signalMissCount = signalMissCount;
        this.scoreMissCount = scoreMissCount;
        this.signalCount = signalCount;
        this.orderSuccessCount = orderSuccessCount;
        this.orderBlockedCount = orderBlockedCount;
        this.minScore = minScore;
        this.nearMissJson = nearMissJson;
        this.executionJson = executionJson;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public LocalDateTime getCheckTime() { return checkTime; }
    public int getScanned() { return scanned; }
    public int getPriceFailCount() { return priceFailCount; }
    public int getHeldSkipCount() { return heldSkipCount; }
    public int getSignalMissCount() { return signalMissCount; }
    public int getScoreMissCount() { return scoreMissCount; }
    public int getSignalCount() { return signalCount; }
    public int getOrderSuccessCount() { return orderSuccessCount; }
    public int getOrderBlockedCount() { return orderBlockedCount; }
    public int getMinScore() { return minScore; }
    public String getNearMissJson() { return nearMissJson; }
    public String getExecutionJson() { return executionJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
