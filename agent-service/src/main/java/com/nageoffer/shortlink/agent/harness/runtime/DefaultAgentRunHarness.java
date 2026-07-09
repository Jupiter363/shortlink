package com.nageoffer.shortlink.agent.harness.runtime;

import com.nageoffer.shortlink.agent.campaignanalysisagent.graph.CampaignAnalysisGraphExecutor;
import com.nageoffer.shortlink.agent.campaignanalysisagent.graph.CampaignAnalysisGraphRequest;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphExecutor;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
public class DefaultAgentRunHarness implements AgentRunHarness {

    private static final String SECURITY_RISK_AGENT_TYPE = "security-risk";

    private final CampaignAnalysisGraphExecutor graphExecutor;

    private final SecurityRiskGraphExecutor securityRiskGraphExecutor;

    public DefaultAgentRunHarness(
            CampaignAnalysisGraphExecutor graphExecutor,
            SecurityRiskGraphExecutor securityRiskGraphExecutor
    ) {
        this.graphExecutor = graphExecutor;
        this.securityRiskGraphExecutor = securityRiskGraphExecutor;
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        String traceId = UUID.randomUUID().toString();
        String agentType = normalizedAgentType(request.agentType());
        if (SECURITY_RISK_AGENT_TYPE.equals(agentType)) {
            return securityRiskGraphExecutor.execute(new SecurityRiskGraphRequest(
                    request.sessionId(),
                    request.username(),
                    request.message(),
                    traceId
            ));
        }
        if (!"campaign-analysis".equals(agentType)) {
            return unsupportedAgentTypeResult(request, traceId, agentType);
        }
        return graphExecutor.execute(new CampaignAnalysisGraphRequest(
                request.sessionId(),
                request.username(),
                request.message(),
                traceId
        ));
    }

    private String normalizedAgentType(String agentType) {
        return StringUtils.hasText(agentType) ? agentType.trim().toLowerCase() : "campaign-analysis";
    }

    private AgentRunResult unsupportedAgentTypeResult(AgentRunRequest request, String traceId, String agentType) {
        String warning = "Unsupported agent type: " + agentType;
        return new AgentRunResult(
                request.sessionId(),
                traceId,
                warning,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(warning)
        );
    }
}
