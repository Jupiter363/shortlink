package com.jupiter.shortlink.agent.securityriskagent.graph;

import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;

public interface SecurityRiskGraphExecutor {

    AgentRunResult execute(SecurityRiskGraphRequest request);
}
