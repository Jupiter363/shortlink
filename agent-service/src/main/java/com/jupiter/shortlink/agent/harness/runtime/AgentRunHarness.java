package com.jupiter.shortlink.agent.harness.runtime;

@FunctionalInterface
public interface AgentRunHarness {

    AgentRunResult run(AgentRunRequest request);
}
