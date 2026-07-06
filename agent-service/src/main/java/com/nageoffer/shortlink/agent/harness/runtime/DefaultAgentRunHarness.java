package com.nageoffer.shortlink.agent.harness.runtime;

import com.nageoffer.shortlink.agent.agent.graph.CampaignAnalysisGraphExecutor;
import com.nageoffer.shortlink.agent.agent.graph.CampaignAnalysisGraphRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DefaultAgentRunHarness implements AgentRunHarness {

    private final CampaignAnalysisGraphExecutor graphExecutor;

    public DefaultAgentRunHarness(CampaignAnalysisGraphExecutor graphExecutor) {
        this.graphExecutor = graphExecutor;
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        String traceId = UUID.randomUUID().toString();
        return graphExecutor.execute(new CampaignAnalysisGraphRequest(
                request.sessionId(),
                request.username(),
                request.message(),
                traceId
        ));
    }
}
