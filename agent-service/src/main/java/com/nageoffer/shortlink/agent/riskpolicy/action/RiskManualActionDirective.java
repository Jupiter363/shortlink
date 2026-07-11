package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;

import java.util.List;

public record RiskManualActionDirective(
        RiskPolicyAction action,
        String timezone,
        List<String> allowedWindows
) {

    public RiskManualActionDirective {
        allowedWindows = allowedWindows == null ? List.of() : List.copyOf(allowedWindows);
    }
}
