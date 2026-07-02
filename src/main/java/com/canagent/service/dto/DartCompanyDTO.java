package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DartCompanyDTO {

    @JsonProperty("status")
    private String status;

    @JsonProperty("message")
    private String message;

    @JsonProperty("corp_name")
    private String corpName;

    @JsonProperty("stock_name")
    private String stockName;

    @JsonProperty("stock_code")
    private String stockCode;

    @JsonProperty("ceo_nm")
    private String ceoName;

    @JsonProperty("corp_cls")
    private String corpClass;

    @JsonProperty("bizrno")
    private String businessNumber;

    @JsonProperty("adres")
    private String address;

    @JsonProperty("induty_name")
    private String industryName;

    @JsonProperty("induty_code")
    private String industryCode;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getCorpName() { return corpName; }
    public void setCorpName(String corpName) { this.corpName = corpName; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public String getCeoName() { return ceoName; }
    public void setCeoName(String ceoName) { this.ceoName = ceoName; }
    public String getCorpClass() { return corpClass; }
    public void setCorpClass(String corpClass) { this.corpClass = corpClass; }
    public String getBusinessNumber() { return businessNumber; }
    public void setBusinessNumber(String businessNumber) { this.businessNumber = businessNumber; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getIndustryName() { return industryName; }
    public void setIndustryName(String industryName) { this.industryName = industryName; }
    public String getIndustryCode() { return industryCode; }
    public void setIndustryCode(String industryCode) { this.industryCode = industryCode; }
}
