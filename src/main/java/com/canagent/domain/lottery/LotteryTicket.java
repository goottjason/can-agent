package com.canagent.domain.lottery;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "lottery_ticket")
public class LotteryTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GameType gameType;

    @Column(nullable = false)
    private int roundNo;              // 회차

    // 정규화 문자열: 로또 "3,7,12,25,33,41" / 연금 "조:6자리" 예 "3:123456"
    @Column(nullable = false, length = 64)
    private String numbers;

    @Column(nullable = false)
    private int amount;               // 구매 금액(원)

    @Column(nullable = false)
    private LocalDateTime purchasedAt;

    @Column(nullable = false)
    private boolean resultChecked = false;

    private Integer rank;             // null=미확인, 0=미당첨, 1..=등수(연금 보너스=8)

    @Column(length = 64)
    private String prizeLabel;        // 예 "1등 (월 700만원 × 20년)"

    @Column(nullable = false)
    private boolean winner = false;

    protected LotteryTicket() {}

    public LotteryTicket(GameType gameType, int roundNo, String numbers, int amount, LocalDateTime purchasedAt) {
        this.gameType = gameType;
        this.roundNo = roundNo;
        this.numbers = numbers;
        this.amount = amount;
        this.purchasedAt = purchasedAt;
    }

    /** 당첨확인 결과 반영. rank>0 이면 당첨. */
    public void applyResult(Integer rank, String prizeLabel) {
        this.rank = rank;
        this.prizeLabel = prizeLabel;
        this.winner = rank != null && rank > 0;
        this.resultChecked = true;
    }

    public Long getId() { return id; }
    public GameType getGameType() { return gameType; }
    public int getRoundNo() { return roundNo; }
    public String getNumbers() { return numbers; }
    public int getAmount() { return amount; }
    public LocalDateTime getPurchasedAt() { return purchasedAt; }
    public boolean isResultChecked() { return resultChecked; }
    public Integer getRank() { return rank; }
    public String getPrizeLabel() { return prizeLabel; }
    public boolean isWinner() { return winner; }
}
