package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public class KrxApiResponse {

    @JsonProperty("response")
    private KrxResponseBody response;

    public KrxResponseBody getResponse() { return response; }
    public void setResponse(KrxResponseBody response) { this.response = response; }

    public boolean isSuccess() {
        return response != null && response.getHeader() != null && response.getHeader().getResultCode() == 0;
    }

    public String getResultMsg() {
        return response != null && response.getHeader() != null ? response.getHeader().getResultMsg() : null;
    }

    public List<Map<String, String>> getItems() {
        return response != null && response.getBody() != null ? response.getBody().getItems() : null;
    }

    public static class KrxResponseBody {

        @JsonProperty("header")
        private KrxHeader header;

        @JsonProperty("body")
        private KrxBody body;

        public KrxHeader getHeader() { return header; }
        public void setHeader(KrxHeader header) { this.header = header; }
        public KrxBody getBody() { return body; }
        public void setBody(KrxBody body) { this.body = body; }
    }

    public static class KrxHeader {

        @JsonProperty("resultCode")
        private int resultCode;

        @JsonProperty("resultMsg")
        private String resultMsg;

        public int getResultCode() { return resultCode; }
        public void setResultCode(int resultCode) { this.resultCode = resultCode; }
        public String getResultMsg() { return resultMsg; }
        public void setResultMsg(String resultMsg) { this.resultMsg = resultMsg; }
    }

    public static class KrxBody {

        @JsonProperty("items")
        private List<Map<String, String>> items;

        public List<Map<String, String>> getItems() { return items; }
        public void setItems(List<Map<String, String>> items) { this.items = items; }
    }
}
