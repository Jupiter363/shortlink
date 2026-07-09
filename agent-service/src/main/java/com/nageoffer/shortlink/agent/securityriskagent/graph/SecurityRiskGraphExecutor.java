package com.nageoffer.shortlink.agent.securityriskagent.graph;

import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;

public interface SecurityRiskGraphExecutor {

    AgentRunResult execute(SecurityRiskGraphRequest request);
}
