package com.nageoffer.shortlink.admin.authority.capability.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;

public final class GroupsListRequest {

    @JsonCreator
    public GroupsListRequest() {
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignoredValue) {
        throw AgentCapabilityException.validation("Unexpected request field: " + fieldName);
    }
}
