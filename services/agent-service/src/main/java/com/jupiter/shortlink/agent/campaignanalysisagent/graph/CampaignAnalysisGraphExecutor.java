package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;

public interface CampaignAnalysisGraphExecutor {

    AgentRunResult execute(CampaignAnalysisGraphRequest request);
}
