package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;

public final class RiskPolicyActionTypes {

    public static final AgentActionType DISABLE_SHORT_LINK =
            new AgentActionType("risk.disable-short-link");

    public static final AgentActionType LIMIT_TIME_WINDOW =
            new AgentActionType("risk.limit-time-window");

    public static final AgentActionType BLOCK_IP =
            new AgentActionType("risk.block-ip");

    private RiskPolicyActionTypes() {
    }
}
