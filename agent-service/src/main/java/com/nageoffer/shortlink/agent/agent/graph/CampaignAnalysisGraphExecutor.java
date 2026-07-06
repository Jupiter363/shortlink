package com.nageoffer.shortlink.agent.agent.graph;

import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;

public interface CampaignAnalysisGraphExecutor {

    AgentRunResult execute(CampaignAnalysisGraphRequest request);
}
