package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class DartApiResponse<T> {

    @JsonProperty("status")
    private String status;

    @JsonProperty("message")
    private String message;

    @JsonProperty("list")
    private List<T> list;

    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public List<T> getList() { return list; }
    public boolean isSuccess() { return "000".equals(status); }
}
