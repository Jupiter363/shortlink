package com.nageoffer.shortlink.agent.harness.action.model;

import java.util.regex.Pattern;

public record AgentActionType(String value) {

    private static final Pattern PATTERN =
            Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+");
    private static final String INVALID_MESSAGE =
            "Agent action type must match [a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+";

    public AgentActionType {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
    }
}
