package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public class KoreaInvestmentPriceResponse {

    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private PriceOutput output;

    public String getRtCd() { return rtCd; }
    public void setRtCd(String rtCd) { this.rtCd = rtCd; }
    public String getMsgCd() { return msgCd; }
    public void setMsgCd(String msgCd) { this.msgCd = msgCd; }
    public String getMsg1() { return msg1; }
    public void setMsg1(String msg1) { this.msg1 = msg1; }
    public PriceOutput getOutput() { return output; }
    public void setOutput(PriceOutput output) { this.output = output; }

    public boolean isSuccess() {
        return "0".equals(rtCd);
    }

    // P2(미국 대전환): int→BigDecimal 무손실. KRW 정수가는 scale 0으로 담겨 기존 동작과 동일하고,
    // USD 소수가(예: 150.25)는 절삭 없이 보존된다.
    public BigDecimal getCurrentPrice() {
        return output != null ? output.getCurrentPriceDecimal() : BigDecimal.ZERO;
    }

    public static KoreaInvestmentPriceResponse error(String message) {
        KoreaInvestmentPriceResponse response = new KoreaInvestmentPriceResponse();
        response.setRtCd("-1");
        response.setMsg1(message);
        return response;
    }

    public static class PriceOutput {
        @JsonProperty("stck_prpr")
        private String currentPrice;

        @JsonProperty("stck_oprc")
        private String openingPrice;

        @JsonProperty("stck_hgpr")
        private String highPrice;

        @JsonProperty("stck_lwpr")
        private String lowPrice;

        @JsonProperty("stck_prdy_vlng")
        private String previousClose;

        @JsonProperty("prdy_vrss")
        private String changeAmount;

        @JsonProperty("prdy_vrss_rate")
        private String changeRate;

        @JsonProperty("acml_vol")
        private String cumulativeVolume;

        @JsonProperty("acml_tr_pbmn")
        private String cumulativeTradingAmount;

        public String getCurrentPrice() { return currentPrice; }
        public void setCurrentPrice(String currentPrice) { this.currentPrice = currentPrice; }
        public String getOpeningPrice() { return openingPrice; }
        public void setOpeningPrice(String openingPrice) { this.openingPrice = openingPrice; }
        public String getHighPrice() { return highPrice; }
        public void setHighPrice(String highPrice) { this.highPrice = highPrice; }
        public String getLowPrice() { return lowPrice; }
        public void setLowPrice(String lowPrice) { this.lowPrice = lowPrice; }
        public String getPreviousClose() { return previousClose; }
        public void setPreviousClose(String previousClose) { this.previousClose = previousClose; }
        public String getChangeAmount() { return changeAmount; }
        public void setChangeAmount(String changeAmount) { this.changeAmount = changeAmount; }
        public String getChangeRate() { return changeRate; }
        public void setChangeRate(String changeRate) { this.changeRate = changeRate; }
        public String getCumulativeVolume() { return cumulativeVolume; }
        public void setCumulativeVolume(String cumulativeVolume) { this.cumulativeVolume = cumulativeVolume; }
        public String getCumulativeTradingAmount() { return cumulativeTradingAmount; }
        public void setCumulativeTradingAmount(String cumulativeTradingAmount) { this.cumulativeTradingAmount = cumulativeTradingAmount; }

        // P2: 정수 파싱(Integer.parseInt) 제거 → BigDecimal 파싱으로 소수 가격 보존.
        public BigDecimal getCurrentPriceDecimal() {
            try {
                return new BigDecimal(currentPrice.replace(",", ""));
            } catch (NumberFormatException | NullPointerException e) {
                return BigDecimal.ZERO;
            }
        }
    }
}
