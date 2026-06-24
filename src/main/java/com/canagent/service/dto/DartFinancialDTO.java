package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

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
}
