package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.harness.action.service.AgentPendingActionService;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskActionProposalContext;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskActionProposalFactory;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskManualActionDirective;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskActionProposalNodeTest {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String RAW_IP = "203.0.113.42";
    private static final String SAFE_IP_HASH = "a".repeat(64);

    @Test
    void persistsFactoryProposalsWithConfiguredTtlAndReturnsSafeTypedViews() {
        RiskActionProposalFactory factory = mock(RiskActionProposalFactory.class);
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        AgentActionProposal proposal = proposal("action-1");
        AgentPendingActionView view = pendingView("action-1");
        when(factory.create(any())).thenReturn(List.of(proposal));
        when(service.propose(proposal, TTL)).thenReturn(view);
        RiskActionProposalNode node = new RiskActionProposalNode(factory, service, TTL);

        Map<String, Object> output = node.apply(interactiveState(
                Map.of("nurl.ink/abc123", "event-1"),
                "session-1"
        ));

        verify(service).propose(proposal, TTL);
        assertThat(output).containsOnlyKeys("pendingActionViews", "visitedNodes");
        assertThat(output.get("pendingActionViews"))
                .isInstanceOf(List.class)
                .asList()
                .containsExactly(view)
                .allSatisfy(item -> assertThat(item).isInstanceOf(AgentPendingActionView.class));
        assertThat(output.get("visitedNodes")).isEqualTo(List.of("risk_action_proposal"));
        assertThat(output.toString())
                .doesNotContain(RAW_IP)
                .doesNotContain("proposal-payload", "payload", "context");
    }

    @Test
    void returnsEmptyTypedViewsWithoutPersistenceWhenFactoryCreatesNoProposals() {
        RiskActionProposalFactory factory = mock(RiskActionProposalFactory.class);
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        when(factory.create(any())).thenReturn(List.of());
        RiskActionProposalNode node = new RiskActionProposalNode(factory, service, TTL);

        Map<String, Object> output = node.apply(new OverAllState(Map.of()));

        assertThat(output).containsEntry("pendingActionViews", List.of());
        assertThat(output).containsEntry("visitedNodes", List.of("risk_action_proposal"));
        verifyNoInteractions(service);
    }

    @Test
    void buildsBatchContextFromEveryGraphStateInput() {
        RiskActionProposalFactory factory = mock(RiskActionProposalFactory.class);
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        when(factory.create(any())).thenReturn(List.of());
        RiskActionProposalNode node = new RiskActionProposalNode(factory, service, TTL);
        ShortLinkRiskProfile profile = profile("abc123", "batch-001");
        ProfileRiskAnalysisContext profileContext = new ProfileRiskAnalysisContext(
                "gid-001",
                null,
                List.of(profile)
        );
        Map<String, String> eventIds = Map.of("nurl.ink/abc123", "event-001");
        RiskSignal signal = signal();
        List<Map<String, Object>> toolExecutions = List.of(Map.of(
                "name", "get_short_link_stats",
                "success", true,
                "data", Map.of(
                        "topIpStats",
                        List.of(Map.of(
                                "ipHash", SAFE_IP_HASH,
                                "maskedIp", "203.0.*.*",
                                "cnt", 42
                        ))
                )
        ));
        OverAllState state = new OverAllState(Map.of(
                "profileRiskContext", profileContext,
                "eventIdsByTarget", eventIds,
                "riskSignals", List.of(signal),
                "toolExecutions", toolExecutions,
                "sessionId", "batch-session",
                "username", "ignored-user",
                "traceId", "trace-001",
                "analysisInput", Map.of("batchId", "batch-001")
        ));

        node.apply(state);

        ArgumentCaptor<RiskActionProposalContext> contextCaptor =
                ArgumentCaptor.forClass(RiskActionProposalContext.class);
        verify(factory).create(contextCaptor.capture());
        assertThat(contextCaptor.getValue()).satisfies(context -> {
            assertThat(context.sourceId()).isEqualTo("risk-profile:batch-001");
            assertThat(context.gid()).isEqualTo("gid-001");
            assertThat(context.sessionId()).isEqualTo("batch-session");
            assertThat(context.proposedBy()).isEqualTo("risk-analysis-worker");
            assertThat(context.traceId()).isEqualTo("trace-001");
            assertThat(context.eventIdsByTarget()).containsExactlyEntriesOf(eventIds);
            assertThat(context.shortLinkProfiles()).containsExactly(profile);
            assertThat(context.riskSignals()).containsExactly(signal);
            assertThat(context.toolExecutions()).containsExactlyElementsOf(toolExecutions);
            assertThat(context.directive()).isEmpty();
        });
    }

    @Test
    void buildsInteractiveContextFromDirectiveAndStableEventOrSessionSource() {
        RiskActionProposalFactory factory = mock(RiskActionProposalFactory.class);
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        when(factory.create(any())).thenReturn(List.of());
        RiskActionProposalNode node = new RiskActionProposalNode(factory, service, TTL);
        Map<String, String> eventIds = new LinkedHashMap<>();
        eventIds.put("nurl.ink/first", "event-first");
        eventIds.put("nurl.ink/second", "event-second");

        node.apply(interactiveState(eventIds, "session-1"));
        node.apply(interactiveState(Map.of(), "session-fallback"));

        ArgumentCaptor<RiskActionProposalContext> contextCaptor =
                ArgumentCaptor.forClass(RiskActionProposalContext.class);
        verify(factory, times(2)).create(contextCaptor.capture());
        RiskActionProposalContext eventContext = contextCaptor.getAllValues().get(0);
        RiskActionProposalContext sessionContext = contextCaptor.getAllValues().get(1);
        assertThat(eventContext.sourceId()).isEqualTo("interactive:event-first");
        assertThat(eventContext.proposedBy()).isEqualTo("analyst-1");
        assertThat(eventContext.directive()).contains(manualDirective());
        assertThat(sessionContext.sourceId()).isEqualTo("interactive:session-fallback");
        assertThat(sessionContext.sourceId()).isNotBlank();
        assertThat(sessionContext.proposedBy()).isEqualTo("analyst-1");
    }

    @Test
    void manualDirectiveSelectsOnlyTheExplicitShortLinkTarget() {
        RiskActionProposalFactory factory = mock(RiskActionProposalFactory.class);
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        when(factory.create(any())).thenReturn(List.of());
        RiskActionProposalNode node = new RiskActionProposalNode(factory, service, TTL);
        ShortLinkRiskProfile first = profile("first", "batch-001");
        ShortLinkRiskProfile target = profile("target", "batch-001");

        node.apply(interactiveState(
                Map.of(
                        "nurl.ink/first", "event-first",
                        "nurl.ink/target", "event-target"
                ),
                "session-1",
                List.of(first, target),
                "gid=gid-001 fullShortUrl=nurl.ink/target action=DISABLE_SHORT_LINK"
        ));

        ArgumentCaptor<RiskActionProposalContext> contextCaptor =
                ArgumentCaptor.forClass(RiskActionProposalContext.class);
        verify(factory).create(contextCaptor.capture());
        assertThat(contextCaptor.getValue().shortLinkProfiles()).containsExactly(target);
    }

    @Test
    void manualDirectiveRejectsAmbiguousMultiProfileContextWithoutExplicitTarget() {
        RiskActionProposalFactory factory = mock(RiskActionProposalFactory.class);
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        when(factory.create(any())).thenReturn(List.of());
        RiskActionProposalNode node = new RiskActionProposalNode(factory, service, TTL);

        node.apply(interactiveState(
                Map.of(),
                "session-1",
                List.of(
                        profile("first", "batch-001"),
                        profile("second", "batch-001")
                ),
                "action=DISABLE_SHORT_LINK"
        ));

        ArgumentCaptor<RiskActionProposalContext> contextCaptor =
                ArgumentCaptor.forClass(RiskActionProposalContext.class);
        verify(factory).create(contextCaptor.capture());
        assertThat(contextCaptor.getValue().shortLinkProfiles()).isEmpty();
    }

    @Test
    void manualDirectiveRejectsDuplicateExplicitShortLinkTargets() {
        RiskActionProposalFactory factory = mock(RiskActionProposalFactory.class);
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        when(factory.create(any())).thenReturn(List.of());
        RiskActionProposalNode node = new RiskActionProposalNode(factory, service, TTL);

        node.apply(interactiveState(
                Map.of(),
                "session-1",
                List.of(
                        profile("first", "batch-001"),
                        profile("second", "batch-001")
                ),
                "fullShortUrl=nurl.ink/first fullShortUrl=nurl.ink/second "
                        + "action=DISABLE_SHORT_LINK"
        ));

        ArgumentCaptor<RiskActionProposalContext> contextCaptor =
                ArgumentCaptor.forClass(RiskActionProposalContext.class);
        verify(factory).create(contextCaptor.capture());
        assertThat(contextCaptor.getValue().shortLinkProfiles()).isEmpty();
    }

    @Test
    void rejectsNullDependenciesAndNonPositiveOrNullTtl() {
        RiskActionProposalFactory factory = mock(RiskActionProposalFactory.class);
        AgentPendingActionService service = mock(AgentPendingActionService.class);

        assertThatThrownBy(() -> new RiskActionProposalNode(null, service, TTL))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("proposalFactory must not be null");
        assertThatThrownBy(() -> new RiskActionProposalNode(factory, null, TTL))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pendingActionService must not be null");
        for (Duration invalidTtl : Arrays.asList(null, Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> new RiskActionProposalNode(factory, service, invalidTtl))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("ttl must be positive");
        }
    }

    @Test
    void noopReturnsEmptyTypedOutputAndNodeVisitWithoutStateOrPersistence() {
        Map<String, Object> output = RiskActionProposalNode.noop().apply(null);

        assertThat(output).containsOnlyKeys("pendingActionViews", "visitedNodes");
        assertThat(output).containsEntry("pendingActionViews", List.of());
        assertThat(output).containsEntry("visitedNodes", List.of("risk_action_proposal"));
    }

    private OverAllState interactiveState(Map<String, String> eventIds, String sessionId) {
        return interactiveState(
                eventIds,
                sessionId,
                List.of(profile("abc123", "batch-001")),
                ""
        );
    }

    private OverAllState interactiveState(
            Map<String, String> eventIds,
            String sessionId,
            List<ShortLinkRiskProfile> profiles,
            String message
    ) {
        return new OverAllState(Map.of(
                "profileRiskContext", new ProfileRiskAnalysisContext(
                        "gid-001",
                        null,
                        profiles
                ),
                "eventIdsByTarget", eventIds,
                "riskSignals", List.of(signal()),
                "toolExecutions", List.of(Map.of(
                        "name", "get_short_link_stats",
                        "success", true,
                        "data", Map.of(
                                "ipHash", SAFE_IP_HASH,
                                "maskedIp", "203.0.*.*"
                        )
                )),
                "manualActionDirective", manualDirective(),
                "sessionId", sessionId,
                "username", "analyst-1",
                "traceId", "trace-interactive",
                "message", message
        ));
    }

    private RiskManualActionDirective manualDirective() {
        return new RiskManualActionDirective(
                RiskPolicyAction.DISABLE_SHORT_LINK,
                null,
                List.of()
        );
    }

    private ShortLinkRiskProfile profile(String shortUri, String batchId) {
        LocalDateTime end = LocalDateTime.of(2026, 7, 11, 10, 0);
        return new ShortLinkRiskProfile(
                "gid-001",
                "nurl.ink",
                shortUri,
                "nurl.ink/" + shortUri,
                end.minusHours(2),
                end,
                null,
                95,
                95,
                RiskLevel.HIGH,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE),
                RiskWatchStatus.NONE,
                List.of(),
                "",
                batchId
        );
    }

    private RiskSignal signal() {
        return new RiskSignal(
                "high",
                95,
                "traffic",
                "TRAFFIC_SPIKE",
                "traffic spike",
                "get_short_link_stats",
                Map.of(),
                Map.of("pv2h", 600),
                Map.of("minimum", 500),
                Map.of("maskedIp", "203.0.*.*"),
                List.of("DISABLE_SHORT_LINK")
        );
    }

    private AgentActionProposal proposal(String actionId) {
        return new AgentActionProposal(
                actionId,
                "security-risk",
                new AgentActionType("risk.disable-short-link"),
                1,
                AgentActionAuthorizationScope.GID,
                "",
                "gid-001",
                "SHORT_LINK",
                "nurl.ink/abc123",
                Map.of("domain", "nurl.ink", "shortUri", "abc123"),
                "Disable short link",
                "Review high-risk short link",
                Map.of("proposal-payload", true, "rawIp", RAW_IP),
                Map.of("rawIp", RAW_IP),
                "risk:event-1:risk.disable-short-link",
                "risk.disable-short-link:gid-001:nurl.ink/abc123",
                "analyst-1",
                "trace-1",
                "event-1",
                "batch-001",
                "session-1"
        );
    }

    private AgentPendingActionView pendingView(String actionId) {
        return new AgentPendingActionView(
                actionId,
                "security-risk",
                "risk.disable-short-link",
                AgentActionStatus.PENDING,
                "gid-001",
                "SHORT_LINK",
                Map.of("domain", "nurl.ink", "shortUri", "abc123"),
                "Disable short link",
                "Review high-risk short link",
                Map.of("riskScore", 95, "eventId", "event-1"),
                0,
                1L,
                LocalDateTime.of(2026, 7, 12, 10, 0),
                null,
                null,
                Map.of(),
                null
        );
    }
}
