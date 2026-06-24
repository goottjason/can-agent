package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class KoreaInvestmentBalanceResponse {

    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output1")
    private List<BalanceItem> output1;

    @JsonProperty("output2")
    private List<AccountSummary> output2;

    public String getRtCd() { return rtCd; }
    public void setRtCd(String rtCd) { this.rtCd = rtCd; }
    public String getMsgCd() { return msgCd; }
    public void setMsgCd(String msgCd) { this.msgCd = msgCd; }
    public String getMsg1() { return msg1; }
    public void setMsg1(String msg1) { this.msg1 = msg1; }
    public List<BalanceItem> getOutput1() { return output1; }
    public void setOutput1(List<BalanceItem> output1) { this.output1 = output1; }
    public List<AccountSummary> getOutput2() { return output2; }
    public void setOutput2(List<AccountSummary> output2) { this.output2 = output2; }

    public boolean isSuccess() {
        return "0".equals(rtCd);
    }

    public static KoreaInvestmentBalanceResponse error(String message) {
        KoreaInvestmentBalanceResponse response = new KoreaInvestmentBalanceResponse();
        response.setRtCd("-1");
        response.setMsg1(message);
        return response;
    }

    public static class BalanceItem {
        @JsonProperty("PDNO")
        private String stockCode;

        @JsonProperty("PRDT_NAME")
        private String stockName;

        @JsonProperty("HLDG_QTY")
        private String holdingQuantity;

        @JsonProperty("Pchs_avg_pric")
        private String averageBuyPrice;

        @JsonProperty("Evlu_amt")
        private String evaluationAmount;

        @JsonProperty("Pchs_amt")
        private String purchaseAmount;

        @JsonProperty("Tot_evlu_pfls_amt")
        private String totalProfitLoss;

        @JsonProperty("Fltt_rt")
        private String fluctuationRate;

        public String getStockCode() { return stockCode; }
        public void setStockCode(String stockCode) { this.stockCode = stockCode; }
        public String getStockName() { return stockName; }
        public void setStockName(String stockName) { this.stockName = stockName; }
        public String getHoldingQuantity() { return holdingQuantity; }
        public void setHoldingQuantity(String holdingQuantity) { this.holdingQuantity = holdingQuantity; }
        public String getAverageBuyPrice() { return averageBuyPrice; }
        public void setAverageBuyPrice(String averageBuyPrice) { this.averageBuyPrice = averageBuyPrice; }
        public String getEvaluationAmount() { return evaluationAmount; }
        public void setEvaluationAmount(String evaluationAmount) { this.evaluationAmount = evaluationAmount; }
        public String getPurchaseAmount() { return purchaseAmount; }
        public void setPurchaseAmount(String purchaseAmount) { this.purchaseAmount = purchaseAmount; }
        public String getTotalProfitLoss() { return totalProfitLoss; }
        public void setTotalProfitLoss(String totalProfitLoss) { this.totalProfitLoss = totalProfitLoss; }
        public String getFluctuationRate() { return fluctuationRate; }
        public void setFluctuationRate(String fluctuationRate) { this.fluctuationRate = fluctuationRate; }
    }

    public static class AccountSummary {
        @JsonProperty("Tot_asst_amt")
        private String totalAssetAmount;

        @JsonProperty("Nsam_amt")
        private String netAssetAmount;

        @JsonProperty("Pchs_amt_smtl")
        private String totalPurchaseAmount;

        @JsonProperty("Frcr_use_psbl_amt")
        private String availableCashAmount;

        public String getTotalAssetAmount() { return totalAssetAmount; }
        public void setTotalAssetAmount(String totalAssetAmount) { this.totalAssetAmount = totalAssetAmount; }
        public String getNetAssetAmount() { return netAssetAmount; }
        public void setNetAssetAmount(String netAssetAmount) { this.netAssetAmount = netAssetAmount; }
        public String getTotalPurchaseAmount() { return totalPurchaseAmount; }
        public void setTotalPurchaseAmount(String totalPurchaseAmount) { this.totalPurchaseAmount = totalPurchaseAmount; }
        public String getAvailableCashAmount() { return availableCashAmount; }
        public void setAvailableCashAmount(String availableCashAmount) { this.availableCashAmount = availableCashAmount; }
    }
}
