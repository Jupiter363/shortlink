package com.nageoffer.shortlink.agent.harness.action.model;

import java.util.List;

public record AgentActionPage<T>(
        List<T> records,
        long total,
        int pageNo,
        int pageSize
) {

    public AgentActionPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
