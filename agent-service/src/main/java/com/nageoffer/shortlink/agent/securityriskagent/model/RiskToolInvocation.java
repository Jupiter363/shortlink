package com.nageoffer.shortlink.agent.securityriskagent.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record RiskToolInvocation(String name, Map<String, Object> arguments) {

    public RiskToolInvocation {
        arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
    }
}
