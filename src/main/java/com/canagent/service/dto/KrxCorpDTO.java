package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class KrxCorpDTO {

    @JsonProperty("srtn_cd")
    private String stockCode;

    @JsonProperty("itms_nm")
    private String itemName;

    @JsonProperty("mrktCtg")
    private String marketCategory;

    @JsonProperty("mktpVs")
    private String marketValue;

    @JsonProperty("clpr")
    private String closingPrice;

    @JsonProperty("vs")
    private String changeAmount;

    @JsonProperty("fltRt")
    private String fluctuationRate;

    @JsonProperty("trqu")
    private String tradingQuantity;

    @JsonProperty("trP")
    private String tradingPrice;

    public String getStockCode() { return stockCode; }
    public String getItemName() { return itemName; }
    public String getMarketCategory() { return marketCategory; }
    public String getMarketValue() { return marketValue; }
    public String getClosingPrice() { return closingPrice; }
    public String getChangeAmount() { return changeAmount; }
    public String getFluctuationRate() { return fluctuationRate; }
    public String getTradingQuantity() { return tradingQuantity; }
    public String getTradingPrice() { return tradingPrice; }

    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public void setMarketCategory(String marketCategory) { this.marketCategory = marketCategory; }
    public void setMarketValue(String marketValue) { this.marketValue = marketValue; }
    public void setClosingPrice(String closingPrice) { this.closingPrice = closingPrice; }
    public void setChangeAmount(String changeAmount) { this.changeAmount = changeAmount; }
    public void setFluctuationRate(String fluctuationRate) { this.fluctuationRate = fluctuationRate; }
    public void setTradingQuantity(String tradingQuantity) { this.tradingQuantity = tradingQuantity; }
    public void setTradingPrice(String tradingPrice) { this.tradingPrice = tradingPrice; }
}
