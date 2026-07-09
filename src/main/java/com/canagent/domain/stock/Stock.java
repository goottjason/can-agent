package com.canagent.domain.stock;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stocks")
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String market;

    private String sector;

    @Column(nullable = false)
    private boolean active;

    // --- US 식별 필드 (P3 additive, 전부 nullable) ---
    // KR 데이터는 이 값이 채워지지 않으며(무영향), P4에서 US 종목 등록 시 사용된다.

    /** 주문·시세 심볼 (예: AAPL). */
    @Column(length = 20)
    private String ticker;

    /** EDGAR 재무조회 조인키(SEC Central Index Key). */
    @Column(length = 20)
    private String cik;

    /** 상장 거래소 (예: NASDAQ, NYSE) — 티커 거래소간 중복 대비. */
    @Column(length = 20)
    private String exchange;

    /** 표시/거래 통화 (예: USD, KRW). */
    @Column(length = 10)
    private String currency;

    /** SEC SIC 분류 코드 — {@code sector}는 사람이 읽는 업종명으로 유지. */
    @Column(length = 10)
    private String sicCode;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    protected Stock() {}

    public Stock(String code, String name, String market, String sector) {
        this.code = code;
        this.name = name;
        this.market = market;
        this.sector = sector;
        this.active = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getMarket() { return market; }
    public String getSector() { return sector; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // --- US 식별 필드 접근자 (P3 additive) ---
    public String getTicker() { return ticker; }
    public String getCik() { return cik; }
    public String getExchange() { return exchange; }
    public String getCurrency() { return currency; }
    public String getSicCode() { return sicCode; }

    public void setTicker(String ticker) { this.ticker = ticker; }
    public void setCik(String cik) { this.cik = cik; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setSicCode(String sicCode) { this.sicCode = sicCode; }

    /**
     * US 식별 필드를 일괄 설정한다(P4 종목 등록용).
     * 기존 4인자 생성자는 그대로 유지되며, 이 메서드는 additive 경로다.
     */
    public void applyUsIdentifiers(String ticker, String cik, String exchange,
                                   String currency, String sicCode) {
        this.ticker = ticker;
        this.cik = cik;
        this.exchange = exchange;
        this.currency = currency;
        this.sicCode = sicCode;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateInfo(String name, String sector) {
        this.name = name;
        this.sector = sector;
        this.updatedAt = LocalDateTime.now();
    }

    public void activate() {
        this.active = true;
        this.updatedAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = LocalDateTime.now();
    }
}
