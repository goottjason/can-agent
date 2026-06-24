package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    public int getCurrentPrice() {
        return output != null ? output.getCurrentPriceInt() : 0;
    }

    public static KoreaInvestmentPriceResponse error(String message) {
        KoreaInvestmentPriceResponse response = new KoreaInvestmentPriceResponse();
        response.setRtCd("-1");
        response.setMsg1(message);
        return response;
    }

    public static class PriceOutput {
        @JsonProperty("STCK_PRCC")
        private String currentPrice;

        @JsonProperty("STCK_OPRC")
        private String openingPrice;

        @JsonProperty("STCK_HGPR")
        private String highPrice;

        @JsonProperty("STCK_LWPR")
        private String lowPrice;

        @JsonProperty("STCK_PRDY_vLNG")
        private String previousClose;

        @JsonProperty("PRDY_VRSS")
        private String changeAmount;

        @JsonProperty("PRDY_VRSS_RATE")
        private String changeRate;

        @JsonProperty("ACML_VOL")
        private String cumulativeVolume;

        @JsonProperty("ACML_TR_PBMN")
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

        public int getCurrentPriceInt() {
            try {
                return Integer.parseInt(currentPrice.replace(",", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
