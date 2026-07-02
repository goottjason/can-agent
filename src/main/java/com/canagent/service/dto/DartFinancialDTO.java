package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DartFinancialDTO {

    @JsonProperty("rcept_no")
    private String receiptNo;

    @JsonProperty("stock_code")
    private String stockCode;

    @JsonProperty("reprt_code")
    private String reportCode;

    @JsonProperty("bsns_year")
    private String businessYear;

    @JsonProperty("thstrm_amount")
    private String currentAmount;

    @JsonProperty("frmtrm_amount")
    private String previousAmount;

    @JsonProperty("fs_div")
    private String fsDivision;

    @JsonProperty("account_nm")
    private String accountName;

    public String getReceiptNo() { return receiptNo; }
    public String getStockCode() { return stockCode; }
    public String getReportCode() { return reportCode; }
    public String getBusinessYear() { return businessYear; }
    public String getCurrentAmount() { return currentAmount; }
    public String getPreviousAmount() { return previousAmount; }
    public String getFsDivision() { return fsDivision; }
    public String getAccountName() { return accountName; }

    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public void setReportCode(String reportCode) { this.reportCode = reportCode; }
    public void setBusinessYear(String businessYear) { this.businessYear = businessYear; }
    public void setCurrentAmount(String currentAmount) { this.currentAmount = currentAmount; }
    public void setPreviousAmount(String previousAmount) { this.previousAmount = previousAmount; }
    public void setFsDivision(String fsDivision) { this.fsDivision = fsDivision; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
}
