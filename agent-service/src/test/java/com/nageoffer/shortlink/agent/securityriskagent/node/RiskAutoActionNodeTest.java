package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyActivationCommand;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskAutoActionNodeTest {

    @Test
    void autoLimitRateOnlyWhenHighScoreHasTwoStrongReasons() {
        RiskPolicyService riskPolicyService = mock(RiskPolicyService.class);
        when(riskPolicyService.canAutoLimitRate(
                RiskLevel.HIGH,
                92,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION)
        )).thenReturn(true);
        when(riskPolicyService.activatePolicy(any())).thenReturn(RiskPolicy.shortLinkPolicy(
                "policy-001",
                "risk:policy:short-link:rate-limit:nurl.ink:high001",
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "high001",
                "{\"action\":\"LIMIT_RATE\"}",
                com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource.AGENT_AUTO,
                "trace-001",
                "event-001"
        ));
        RiskAutoActionNode node = new RiskAutoActionNode(riskPolicyService, new AgentProperties());
        ProfileRiskAnalysisContext context = new ProfileRiskAnalysisContext(
                "gid-001",
                null,
                List.of(
                        profile("high001", 92, Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION), List.of()),
                        profile("oneReason", 90, Set.of(RiskReasonCode.TRAFFIC_SPIKE), List.of()),
                        profile("manualDisable", 95, Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION), List.of("DISABLE_SHORT_LINK")),
                        profile("manualBlock", 95, Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION), List.of("BLOCK_IP")),
                        profile("manualWindow", 95, Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION), List.of("LIMIT_TIME_WINDOW"))
                )
        );

        Map<String, Object> output = node.apply(context, Map.of("nurl.ink/high001", "event-001"), "trace-001");

        assertThat(output.get("activatedPolicies").toString())
                .contains("high001")
                .doesNotContain("oneReason")
                .doesNotContain("manualDisable")
                .doesNotContain("manualBlock")
                .doesNotContain("manualWindow");
        ArgumentCaptor<RiskPolicyActivationCommand> commandCaptor = ArgumentCaptor.forClass(RiskPolicyActivationCommand.class);
        verify(riskPolicyService).activatePolicy(commandCaptor.capture());
        assertThat(commandCaptor.getValue().action()).isEqualTo(RiskPolicyAction.LIMIT_RATE);
        assertThat(commandCaptor.getValue().eventId()).isEqualTo("event-001");
        assertThat(commandCaptor.getValue().policyPayloadJson())
                .contains("\"limit\":60")
                .contains("\"windowSeconds\":60");
    }

    @Test
    void doesNotActivateWhenNoProfileMeetsAutoRule() {
        RiskPolicyService riskPolicyService = mock(RiskPolicyService.class);
        RiskAutoActionNode node = new RiskAutoActionNode(riskPolicyService, new AgentProperties());
        ProfileRiskAnalysisContext context = new ProfileRiskAnalysisContext(
                "gid-001",
                null,
                List.of(profile("oneReason", 90, Set.of(RiskReasonCode.TRAFFIC_SPIKE), List.of()))
        );

        Map<String, Object> output = node.apply(context, Map.of(), "trace-001");

        assertThat(output.get("activatedPolicies")).isEqualTo(List.of());
        verify(riskPolicyService, never()).activatePolicy(any());
    }

    @Test
    void retryReusesDeterministicAutoPolicyId() {
        RiskPolicyService riskPolicyService = mock(RiskPolicyService.class);
        when(riskPolicyService.canAutoLimitRate(
                RiskLevel.HIGH,
                92,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION)
        )).thenReturn(true);
        when(riskPolicyService.activatePolicy(any())).thenAnswer(invocation -> {
            RiskPolicyActivationCommand command = invocation.getArgument(0);
            return RiskPolicy.shortLinkPolicy(
                    command.policyId(),
                    "risk:policy:short-link:rate-limit:nurl.ink:high001",
                    command.action(),
                    command.gid(),
                    command.domain(),
                    command.shortUri(),
                    command.policyPayloadJson(),
                    command.source(),
                    command.traceId(),
                    command.eventId()
            );
        });
        RiskAutoActionNode node = new RiskAutoActionNode(riskPolicyService, new AgentProperties());
        ProfileRiskAnalysisContext context = new ProfileRiskAnalysisContext(
                "gid-001",
                null,
                List.of(profile(
                        "high001",
                        92,
                        Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                        List.of()
                ))
        );

        node.apply(context, Map.of("nurl.ink/high001", "event-stable"), "trace-stable");
        node.apply(context, Map.of("nurl.ink/high001", "event-stable"), "trace-stable");

        ArgumentCaptor<RiskPolicyActivationCommand> commandCaptor =
                ArgumentCaptor.forClass(RiskPolicyActivationCommand.class);
        verify(riskPolicyService, times(2)).activatePolicy(commandCaptor.capture());
        assertThat(commandCaptor.getAllValues())
                .extracting(RiskPolicyActivationCommand::policyId)
                .containsOnly(commandCaptor.getAllValues().get(0).policyId());
    }

    private ShortLinkRiskProfile profile(
            String shortUri,
            int riskScore,
            Set<RiskReasonCode> reasons,
            List<String> latestPolicyActions
    ) {
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        return new ShortLinkRiskProfile(
                "gid-001",
                "nurl.ink",
                shortUri,
                "nurl.ink/" + shortUri,
                endTime.minusHours(2),
                endTime,
                new ShortLinkRiskMetrics(600, 50, 900, 300, 2100, 1200, 8.0, 0.82, 0.78, 0.50, 0.65, 0.60, 12.0, 0.74, 0.88),
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                reasons,
                RiskWatchStatus.NONE,
                latestPolicyActions,
                ""
        );
    }
}
