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

    public void updateInfo(String name, String sector) {
        this.name = name;
        this.sector = sector;
        this.updatedAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = LocalDateTime.now();
    }
}
