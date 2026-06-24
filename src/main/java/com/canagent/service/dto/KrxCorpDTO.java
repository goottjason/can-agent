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
    public String getClosingPrice() { return closingPrice; }
    public String getChangeAmount() { return changeAmount; }
    public String getFluctuationRate() { return fluctuationRate; }
    public String getTradingQuantity() { return tradingQuantity; }
}
