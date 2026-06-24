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
    public String getListedStockCount() { return listedStockCount; }

    public void setBaseDate(String baseDate) { this.baseDate = baseDate; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public void setIsinCode(String isinCode) { this.isinCode = isinCode; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public void setClosingPrice(String closingPrice) { this.closingPrice = closingPrice; }
    public void setChangeAmount(String changeAmount) { this.changeAmount = changeAmount; }
    public void setFluctuationRate(String fluctuationRate) { this.fluctuationRate = fluctuationRate; }
    public void setOpeningPrice(String openingPrice) { this.openingPrice = openingPrice; }
    public void setHighPrice(String highPrice) { this.highPrice = highPrice; }
    public void setLowPrice(String lowPrice) { this.lowPrice = lowPrice; }
    public void setTradingQuantity(String tradingQuantity) { this.tradingQuantity = tradingQuantity; }
    public void setTradingPrice(String tradingPrice) { this.tradingPrice = tradingPrice; }
    public void setListedStockCount(String listedStockCount) { this.listedStockCount = listedStockCount; }
}
