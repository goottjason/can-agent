package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KrxApiResponse {

    @JsonProperty("response")
    private KrxResponseBody response;

    public KrxResponseBody getResponse() { return response; }
    public void setResponse(KrxResponseBody response) { this.response = response; }

    public boolean isSuccess() {
        return response != null && response.getHeader() != null && "00".equals(response.getHeader().getResultCode());
    }

    public String getResultMsg() {
        return response != null && response.getHeader() != null ? response.getHeader().getResultMsg() : null;
    }

    public int getTotalCount() {
        return response != null && response.getBody() != null ? response.getBody().getTotalCount() : 0;
    }

    public List<Map<String, String>> getItems() {
        if (response == null || response.getBody() == null || response.getBody().getItems() == null) {
            return Collections.emptyList();
        }
        return response.getBody().getItems().getItems();
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
        private String resultCode;

        @JsonProperty("resultMsg")
        private String resultMsg;

        public String getResultCode() { return resultCode; }
        public void setResultCode(String resultCode) { this.resultCode = resultCode; }
        public String getResultMsg() { return resultMsg; }
        public void setResultMsg(String resultMsg) { this.resultMsg = resultMsg; }
    }

    public static class KrxBody {

        @JsonProperty("numOfRows")
        private int numOfRows;

        @JsonProperty("pageNo")
        private int pageNo;

        @JsonProperty("totalCount")
        private int totalCount;

        @JsonProperty("items")
        private KrxItems items;

        public int getNumOfRows() { return numOfRows; }
        public int getPageNo() { return pageNo; }
        public int getTotalCount() { return totalCount; }
        public KrxItems getItems() { return items; }
    }

    public static class KrxItems {

        @JsonProperty("item")
        private List<Map<String, String>> items;

        public List<Map<String, String>> getItems() {
            if (items == null) return Collections.emptyList();
            return items;
        }
    }
}
