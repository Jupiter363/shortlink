package com.jupiter.shortlink.agent.harness.runtime;

import com.jupiter.shortlink.agent.campaignanalysisagent.graph.CampaignAnalysisGraphExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.graph.CampaignAnalysisGraphRequest;
import com.jupiter.shortlink.agent.securityriskagent.graph.SecurityRiskGraphExecutor;
import com.jupiter.shortlink.agent.securityriskagent.graph.SecurityRiskGraphRequest;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
public class DefaultAgentRunHarness implements AgentRunHarness {

    private static final String SECURITY_RISK_AGENT_TYPE = "security-risk";

    private final CampaignAnalysisGraphExecutor graphExecutor;

    private final SecurityRiskGraphExecutor securityRiskGraphExecutor;
    private com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignPublicRequestService campaignRequests;
    private CampaignRuntimeIdentityGuard campaignIdentity;

    @org.springframework.beans.factory.annotation.Autowired
    public void setCampaignIdentity(CampaignRuntimeIdentityGuard identity) {
        this.campaignIdentity=java.util.Objects.requireNonNull(identity);
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCampaignRequests(com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignPublicRequestService requests) {
        this.campaignRequests=java.util.Objects.requireNonNull(requests);
    }

    public DefaultAgentRunHarness(
            CampaignAnalysisGraphExecutor graphExecutor,
            SecurityRiskGraphExecutor securityRiskGraphExecutor) {
        this.graphExecutor = graphExecutor;
        this.securityRiskGraphExecutor = securityRiskGraphExecutor;
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        String traceId = UUID.randomUUID().toString();
        String agentType = normalizedAgentType(request.agentType());
        if (SECURITY_RISK_AGENT_TYPE.equals(agentType)) {
            return securityRiskGraphExecutor.execute(
                    new SecurityRiskGraphRequest(
                            request.sessionId(),
                            request.username(),
                            request.message(),
                            traceId,
                            null,
                            request.principal()));
        }
        if (!"campaign-analysis".equals(agentType)) {
            return unsupportedAgentTypeResult(request, traceId, agentType);
        }
        if (campaignRequests != null) {
            if (request.clientCapabilities().contains("campaign-response/v2")
                    || request.operation()!=AgentRunRequest.Operation.NEW || request.previousRunId()!=null)
                return campaignRequests.run(request);
        }
        if (request.operation()!=AgentRunRequest.Operation.NEW || request.previousRunId()!=null)
            throw new IllegalStateException("CAMPAIGN_DURABLE_RUNTIME_UNAVAILABLE");
        if (campaignIdentity != null && campaignIdentity.recordedRequest(request)) {
            if (campaignRequests == null) throw new IllegalStateException("CAMPAIGN_DURABLE_RUNTIME_UNAVAILABLE");
            throw new IllegalArgumentException("CAMPAIGN_CLIENT_UPGRADE_REQUIRED");
        }
        return graphExecutor.execute(
                new CampaignAnalysisGraphRequest(
                        request.sessionId(),
                        request.username(),
                        request.message(),
                        traceId,
                        request.principal(),
                        request.requestKey()));
    }

    private String normalizedAgentType(String agentType) {
        return StringUtils.hasText(agentType)
                ? agentType.trim().toLowerCase()
                : "campaign-analysis";
    }

    private AgentRunResult unsupportedAgentTypeResult(
            AgentRunRequest request, String traceId, String agentType) {
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
                List.of(warning));
    }
}
