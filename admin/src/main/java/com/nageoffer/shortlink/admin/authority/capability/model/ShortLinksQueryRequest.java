package com.nageoffer.shortlink.admin.authority.capability.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;

public final class ShortLinksQueryRequest {

    private final String gid;

    private final Long current;

    private final Long size;

    private final Sort sort;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public ShortLinksQueryRequest(
            @JsonProperty(value = "gid", required = true) String gid,
            @JsonProperty(value = "current", required = true) Long current,
            @JsonProperty(value = "size", required = true) Long size,
            @JsonProperty(value = "sort", required = true) Sort sort
    ) {
        this.gid = gid;
        this.current = current;
        this.size = size;
        this.sort = sort;
    }

    public String gid() {
        return gid;
    }

    public Long current() {
        return current;
    }

    public Long size() {
        return size;
    }

    public Sort sort() {
        return sort;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignoredValue) {
        throw AgentCapabilityException.validation("Unexpected request field: " + fieldName);
    }

    public enum Sort {
        CREATED_AT_DESC,
        TODAY_PV_DESC,
        TODAY_UV_DESC,
        TODAY_UIP_DESC,
        TOTAL_PV_DESC,
        TOTAL_UV_DESC,
        TOTAL_UIP_DESC
    }
}
