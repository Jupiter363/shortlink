package com.nageoffer.shortlink.agent.harness.runtime;

import com.nageoffer.shortlink.agent.campaignanalysisagent.graph.CampaignAnalysisGraphExecutor;
import com.nageoffer.shortlink.agent.campaignanalysisagent.graph.CampaignAnalysisGraphRequest;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphExecutor;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentRunHarnessTest {

    @Test
    void runDelegatesToCampaignAnalysisGraphByDefaultWithTraceId() {
        CapturingCampaignAnalysisGraphExecutor campaignGraphExecutor = new CapturingCampaignAnalysisGraphExecutor();
        CapturingSecurityRiskGraphExecutor securityRiskGraphExecutor = new CapturingSecurityRiskGraphExecutor();
        DefaultAgentRunHarness harness = new DefaultAgentRunHarness(campaignGraphExecutor, securityRiskGraphExecutor);

        AgentRunResult result = harness.run(new AgentRunRequest(
                "session-1",
                null,
                "zhangsan",
                "analyze recent campaign data"
        ));

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.traceId()).isEqualTo(campaignGraphExecutor.request.traceId());
        assertThat(result.answer()).isEqualTo("campaign analysis result");
        assertThat(campaignGraphExecutor.request.traceId()).isNotBlank();
        assertThat(campaignGraphExecutor.request.sessionId()).isEqualTo("session-1");
        assertThat(campaignGraphExecutor.request.username()).isEqualTo("zhangsan");
        assertThat(campaignGraphExecutor.request.message()).isEqualTo("analyze recent campaign data");
        assertThat(securityRiskGraphExecutor.request).isNull();
    }

    @Test
    void runDelegatesToSecurityRiskGraphWhenAgentTypeIsSecurityRisk() {
        CapturingCampaignAnalysisGraphExecutor campaignGraphExecutor = new CapturingCampaignAnalysisGraphExecutor();
        CapturingSecurityRiskGraphExecutor securityRiskGraphExecutor = new CapturingSecurityRiskGraphExecutor();
        DefaultAgentRunHarness harness = new DefaultAgentRunHarness(campaignGraphExecutor, securityRiskGraphExecutor);

        AgentRunResult result = harness.run(new AgentRunRequest(
                "session-2",
                "security-risk",
                "zhangsan",
                "analyze gid=g1 security risk"
        ));

        assertThat(result.sessionId()).isEqualTo("session-2");
        assertThat(result.traceId()).isEqualTo(securityRiskGraphExecutor.request.traceId());
        assertThat(result.answer()).isEqualTo("security risk result");
        assertThat(campaignGraphExecutor.request).isNull();
        assertThat(securityRiskGraphExecutor.request.traceId()).isNotBlank();
        assertThat(securityRiskGraphExecutor.request.sessionId()).isEqualTo("session-2");
        assertThat(securityRiskGraphExecutor.request.username()).isEqualTo("zhangsan");
        assertThat(securityRiskGraphExecutor.request.message()).isEqualTo("analyze gid=g1 security risk");
    }

    @Test
    void runRejectsUnsupportedAgentTypeWithoutFallingBackToCampaignGraph() {
        CapturingCampaignAnalysisGraphExecutor campaignGraphExecutor = new CapturingCampaignAnalysisGraphExecutor();
        CapturingSecurityRiskGraphExecutor securityRiskGraphExecutor = new CapturingSecurityRiskGraphExecutor();
        DefaultAgentRunHarness harness = new DefaultAgentRunHarness(campaignGraphExecutor, securityRiskGraphExecutor);

        AgentRunResult result = harness.run(new AgentRunRequest(
                "session-3",
                "securityrisk",
                "zhangsan",
                "analyze gid=g1 security risk"
        ));

        assertThat(result.sessionId()).isEqualTo("session-3");
        assertThat(result.answer()).contains("Unsupported agent type");
        assertThat(result.warnings()).contains("Unsupported agent type: securityrisk");
        assertThat(result.dataSources()).isEmpty();
        assertThat(campaignGraphExecutor.request).isNull();
        assertThat(securityRiskGraphExecutor.request).isNull();
    }

    private static class CapturingCampaignAnalysisGraphExecutor implements CampaignAnalysisGraphExecutor {

        private CampaignAnalysisGraphRequest request;

        @Override
        public AgentRunResult execute(CampaignAnalysisGraphRequest request) {
            this.request = request;
            return new AgentRunResult(
                    request.sessionId(),
                    request.traceId(),
                    "campaign analysis result",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Map.of("type", "graph", "name", "campaign-analysis-graph")),
                    List.of(),
                    List.of()
            );
        }
    }

    private static class CapturingSecurityRiskGraphExecutor implements SecurityRiskGraphExecutor {

        private SecurityRiskGraphRequest request;

        @Override
        public AgentRunResult execute(SecurityRiskGraphRequest request) {
            this.request = request;
            return new AgentRunResult(
                    request.sessionId(),
                    request.traceId(),
                    "security risk result",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Map.of("type", "graph", "name", "security-risk-graph")),
                    List.of(),
                    List.of()
            );
        }
    }
}
