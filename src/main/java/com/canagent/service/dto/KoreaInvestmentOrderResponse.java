package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class KoreaInvestmentOrderResponse {

    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private OrderOutput output;

    public String getRtCd() { return rtCd; }
    public void setRtCd(String rtCd) { this.rtCd = rtCd; }
    public String getMsgCd() { return msgCd; }
    public void setMsgCd(String msgCd) { this.msgCd = msgCd; }
    public String getMsg1() { return msg1; }
    public void setMsg1(String msg1) { this.msg1 = msg1; }
    public OrderOutput getOutput() { return output; }
    public void setOutput(OrderOutput output) { this.output = output; }

    public boolean isSuccess() {
        return "0".equals(rtCd);
    }

    public String getOrderNo() {
        return output != null ? output.getOrderNo() : null;
    }

    public static KoreaInvestmentOrderResponse error(String message) {
        KoreaInvestmentOrderResponse response = new KoreaInvestmentOrderResponse();
        response.setRtCd("-1");
        response.setMsg1(message);
        return response;
    }

    public static class OrderOutput {
        @JsonProperty("ODNO")
        private String orderNo;

        @JsonProperty("ORD_TMD")
        private String orderTime;

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public String getOrderTime() { return orderTime; }
        public void setOrderTime(String orderTime) { this.orderTime = orderTime; }
    }
}
