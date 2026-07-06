package com.nageoffer.shortlink.agent.harness.runtime;

@FunctionalInterface
public interface AgentRunHarness {

    AgentRunResult run(AgentRunRequest request);
}
