package com.jupiter.shortlink.agent.securityriskagent.node;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;

import org.junit.jupiter.api.Test;

import java.util.*;

class RiskAutoActionNodeTest {
    @Test
    void cancelledExecutionCannotStartAnotherTargetCommand() {
        var service = mock(RiskPolicyService.class);
        var profile = StatsTestFixtures.profile();
        when(service.canAutoLimitRate(any(), anyInt(), any())).thenReturn(true);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(service.autoLimitRate(any(), any(), anyString(), anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(4).run();
            cancelled.set(true);
            return Map.of("status", "COMMITTED");
        });
        var state = new com.alibaba.cloud.ai.graph.OverAllState(Map.of(
                "profileRiskContext", new ProfileRiskAnalysisContext("g1", null, List.of(profile, profile)),
                "principal", StatsTestFixtures.PRINCIPAL.toState(), "traceId", "trace"));
        assertThatThrownBy(() -> new RiskAutoActionNode(service, new AgentProperties()).apply(state, () -> {
            if (cancelled.get()) throw new IllegalStateException("execution cancelled");
        })).hasMessage("execution cancelled");
        verify(service, times(1)).autoLimitRate(any(), any(), anyString(), anyString(), any(Runnable.class));
    }

    @Test
    void automaticCommandsCarryPrincipalAndStableEvidenceIdentityAcrossRetries() {
        var service = mock(RiskPolicyService.class);
        var profile = StatsTestFixtures.profile();
        var context = new ProfileRiskAnalysisContext("g1", null, List.of(profile));
        var actionProfile = context.shortLinkProfiles().get(0);
        when(service.canAutoLimitRate(
                        profile.riskLevel(), profile.riskScore(), profile.reasonCodes()))
                .thenReturn(true);
        when(service.autoLimitRate(
                        eq(StatsTestFixtures.PRINCIPAL), eq(actionProfile), anyString(), anyString(), any(Runnable.class)))
                .thenReturn(Map.of("status", "COMMITTED"));
        var node = new RiskAutoActionNode(service, new AgentProperties());
        var first = node.apply(context, Map.of(), "trace-1", StatsTestFixtures.PRINCIPAL);
        var second = node.apply(context, Map.of(), "trace-2", StatsTestFixtures.PRINCIPAL);
        var ids = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service, times(2))
                .autoLimitRate(
                        eq(StatsTestFixtures.PRINCIPAL), eq(actionProfile), ids.capture(), anyString(), any(Runnable.class));
        assertThat(ids.getAllValues().get(0)).isEqualTo(ids.getAllValues().get(1));
        assertThat(first.get("activatedPolicies").toString()).contains("COMMITTED");
        assertThat(second.get("activatedPolicies").toString()).contains("COMMITTED");
    }

    @Test
    void lowConfidenceCannotEmitPolicyCommands() {
        var service = mock(RiskPolicyService.class);
        var node = new RiskAutoActionNode(service, new AgentProperties());
        var result =
                node.apply(
                        new ProfileRiskAnalysisContext(
                                "g1", null, List.of(StatsTestFixtures.profile())),
                        Map.of(),
                        "trace",
                        StatsTestFixtures.PRINCIPAL);
        assertThat(result.get("activatedPolicies")).isEqualTo(List.of());
        verify(service, never()).autoLimitRate(any(), any(), anyString(), anyString(), any(Runnable.class));
    }

    @Test
    void nonCommittedReceiptIsNotReportedAsCommitted() {
        var service = mock(RiskPolicyService.class);
        var profile = StatsTestFixtures.profile();
        when(service.canAutoLimitRate(any(), anyInt(), any())).thenReturn(true);
        when(service.autoLimitRate(any(), any(), anyString(), anyString(), any(Runnable.class)))
                .thenReturn(Map.of("status", "EVIDENCE_UNAVAILABLE"));
        var result =
                new RiskAutoActionNode(service, new AgentProperties())
                        .apply(
                                new ProfileRiskAnalysisContext("g1", null, List.of(profile)),
                                Map.of(),
                                "trace",
                                StatsTestFixtures.PRINCIPAL);
        assertThat(result.get("activatedPolicies").toString())
                .contains("EVIDENCE_UNAVAILABLE")
                .doesNotContain("COMMITTED");
    }
}
