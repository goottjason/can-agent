package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BalanceItem {
        @JsonProperty("pdno")
        private String stockCode;

        @JsonProperty("prdt_name")
        private String stockName;

        @JsonProperty("hldg_qty")
        private String holdingQuantity;

        @JsonProperty("pchs_avg_pric")
        private String averageBuyPrice;

        @JsonProperty("evlu_amt")
        private String evaluationAmount;

        @JsonProperty("pchs_amt")
        private String purchaseAmount;

        @JsonProperty("tot_evlu_pfls_amt")
        private String totalProfitLoss;

        @JsonProperty("fltt_rt")
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccountSummary {
        @JsonProperty("dnca_tot_amt")
        private String depositTotalAmount;

        @JsonProperty("nass_amt")
        private String netAssetAmount;

        @JsonProperty("pchs_amt_smtl_amt")
        private String totalPurchaseAmount;

        @JsonProperty("scts_evlu_amt")
        private String stockEvaluationAmount;

        @JsonProperty("tot_evlu_amt")
        private String totalEvaluationAmount;

        @JsonProperty("prvs_rcdl_excc_amt")
        private String withdrawableAmount;

        @JsonProperty("nxdy_excc_amt")
        private String nextDayExccAmount;

        public String getDepositTotalAmount() { return depositTotalAmount; }
        public String getNetAssetAmount() { return netAssetAmount; }
        public String getTotalPurchaseAmount() { return totalPurchaseAmount; }
        public String getStockEvaluationAmount() { return stockEvaluationAmount; }
        public String getTotalEvaluationAmount() { return totalEvaluationAmount; }
        public String getWithdrawableAmount() { return withdrawableAmount; }
        public String getNextDayExccAmount() { return nextDayExccAmount; }

        public String getTotalAssetAmount() {
            if (totalEvaluationAmount != null && !totalEvaluationAmount.isBlank()) return totalEvaluationAmount;
            return "0";
        }

        public String getAvailableCashAmount() {
            if (withdrawableAmount != null && !withdrawableAmount.isBlank()) return withdrawableAmount;
            if (depositTotalAmount != null && !depositTotalAmount.isBlank()) return depositTotalAmount;
            return "0";
        }
    }
}
