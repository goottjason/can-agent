package com.canagent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DartCompanyDTO {

    @JsonProperty("status")
    private String status;

    @JsonProperty("corp_name")
    private String corpName;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCorpName() { return corpName; }
    public void setCorpName(String corpName) { this.corpName = corpName; }
}
