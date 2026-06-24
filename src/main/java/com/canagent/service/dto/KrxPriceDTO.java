package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class KrxPriceDTO {

    @JsonProperty("basDt")
    private String baseDate;

    @JsonProperty("srtnCd")
    private String stockCode;

    @JsonProperty("isinCd")
    private String isinCode;

    @JsonProperty("itmsNm")
    private String itemName;

    @JsonProperty("clpr")
    private String closingPrice;

    @JsonProperty("vs")
    private String changeAmount;

    @JsonProperty("fltRt")
    private String fluctuationRate;

    @JsonProperty("mkp")
    private String openingPrice;

    @JsonProperty("hipr")
    private String highPrice;

    @JsonProperty("lopr")
    private String lowPrice;

    @JsonProperty("trqu")
    private String tradingQuantity;

    @JsonProperty("trP")
    private String tradingPrice;

    @JsonProperty("lstgStCnt")
    private String listedStockCount;

    public String getBaseDate() { return baseDate; }
    public String getStockCode() { return stockCode; }
    public String getIsinCode() { return isinCode; }
    public String getItemName() { return itemName; }
    public String getClosingPrice() { return closingPrice; }
    public String getChangeAmount() { return changeAmount; }
    public String getFluctuationRate() { return fluctuationRate; }
    public String getOpeningPrice() { return openingPrice; }
    public String getHighPrice() { return highPrice; }
    public String getLowPrice() { return lowPrice; }
    public String getTradingQuantity() { return tradingQuantity; }
    public String getTradingPrice() { return tradingPrice; }
}
