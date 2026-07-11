package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.service.AgentPendingActionService;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskActionProposalContext;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskActionProposalFactory;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskIpEvidenceExtractor;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskManualActionDirective;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPayloadValidator;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionTypes;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskActionProposalFactoryTest {

    private static final String TARGET = "nurl.ink/abc123";
    private static final String EVENT_ID = "event-001";
    private static final String IP_HASH = "a".repeat(64);
    private static final Set<RiskReasonCode> STRONG_REASONS = Set.of(
            RiskReasonCode.TRAFFIC_SPIKE,
            RiskReasonCode.IP_CONCENTRATION,
            RiskReasonCode.HIGH_REPEAT_VISIT
    );

    @Test
    void productionFactoryIsAComponentWithOneAutowiredConstructor() {
        assertThat(RiskActionProposalFactory.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(RiskActionProposalFactory.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> {
                    Constructor<?> value = (Constructor<?>) constructor;
                    assertThat(value.isAnnotationPresent(Autowired.class)).isTrue();
                    assertThat(value.getParameterTypes()).containsExactly(
                            AgentProperties.class,
                            AgentPendingActionService.class,
                            RiskPolicyActionPayloadValidator.class,
                            RiskIpEvidenceExtractor.class
                    );
                });
    }

    @Test
    void highConfidenceBatchProfileCreatesDeterministicDisableProposal() {
        Fixture fixture = fixture();

        List<AgentActionProposal> proposals = fixture.factory().create(batchContext(
                List.of(profile("abc123", 95, STRONG_REASONS)),
                Map.of(TARGET, EVENT_ID)
        ));

        assertThat(proposals).singleElement().satisfies(proposal -> {
            assertThat(proposal.actionType()).isEqualTo(RiskPolicyActionTypes.DISABLE_SHORT_LINK);
            assertThat(proposal.authorizationScope()).isEqualTo(AgentActionAuthorizationScope.GID);
            assertThat(proposal.gid()).isEqualTo("g1");
            assertThat(proposal.targetType()).isEqualTo("SHORT_LINK");
            assertThat(proposal.targetKey()).isEqualTo(TARGET);
            assertThat(proposal.idempotencyKey()).isEqualTo(
                    "risk:batch-1:g1:nurl.ink:abc123:risk.disable-short-link"
            );
            assertThat(proposal.activeSlotKey()).isEqualTo(
                    "risk.disable-short-link:g1:" + TARGET
            );
            assertThat(proposal.eventId()).isEqualTo(EVENT_ID);
            assertThat(proposal.batchId()).isEqualTo("batch-1");
            assertThat(proposal.payload())
                    .containsEntry("action", RiskPolicyAction.DISABLE_SHORT_LINK)
                    .containsEntry("domain", "nurl.ink")
                    .containsEntry("shortUri", "abc123")
                    .doesNotContainKeys("ipHash", "timezone", "allowedWindows", "expireTime");
            assertThat(proposal.evidence())
                    .containsEntry("riskScore", 95)
                    .containsEntry("eventId", EVENT_ID);
        });

        List<AgentActionProposal> retry = fixture.factory().create(batchContext(
                List.of(profile("abc123", 95, STRONG_REASONS)),
                Map.of(TARGET, EVENT_ID)
        ));
        assertThat(retry).extracting(AgentActionProposal::actionId)
                .isEqualTo(proposals.stream().map(AgentActionProposal::actionId).toList());
    }

    @Test
    void batchDisableRequiresScoreThresholdStrongReasonsAndPersistedEvent() {
        Fixture fixture = fixture();

        assertThat(fixture.factory().create(batchContext(
                List.of(profile("abc123", 89, STRONG_REASONS)),
                Map.of(TARGET, EVENT_ID)
        ))).isEmpty();
        assertThat(fixture.factory().create(batchContext(
                List.of(profile("abc123", 95, Set.of(
                        RiskReasonCode.TRAFFIC_SPIKE,
                        RiskReasonCode.IP_CONCENTRATION
                ))),
                Map.of(TARGET, EVENT_ID)
        ))).isEmpty();
        assertThat(fixture.factory().create(batchContext(
                List.of(profile("abc123", 95, STRONG_REASONS)),
                Map.of()
        ))).isEmpty();
    }

    @Test
    void batchProposalCountUsesProfileTopCandidateLimitAfterFiltering() {
        Fixture fixture = fixture();
        fixture.properties().getRisk().getProfile().setTopCandidateSize(2);
        List<ShortLinkRiskProfile> profiles = List.of(
                profile("one", 96, STRONG_REASONS),
                profile("two", 95, STRONG_REASONS),
                profile("three", 94, STRONG_REASONS)
        );
        Map<String, String> events = Map.of(
                "nurl.ink/one", "event-1",
                "nurl.ink/two", "event-2",
                "nurl.ink/three", "event-3"
        );

        assertThat(fixture.factory().create(batchContext(profiles, events)))
                .extracting(AgentActionProposal::targetKey)
                .containsExactly("nurl.ink/one", "nurl.ink/two");
    }

    @Test
    void explicitTimeWindowDirectiveCreatesValidatedInteractiveProposal() {
        Fixture fixture = fixture();
        RiskManualActionDirective directive = new RiskManualActionDirective(
                RiskPolicyAction.LIMIT_TIME_WINDOW,
                "Asia/Shanghai",
                List.of("08:00-12:00", "13:00-17:00")
        );

        List<AgentActionProposal> proposals = fixture.factory().create(interactiveContext(
                directive,
                List.of(profile("abc123", 50, Set.of(RiskReasonCode.REGION_CONCENTRATION))),
                Map.of(TARGET, EVENT_ID),
                List.of()
        ));

        assertThat(proposals).singleElement().satisfies(proposal -> {
            assertThat(proposal.actionType()).isEqualTo(RiskPolicyActionTypes.LIMIT_TIME_WINDOW);
            assertThat(proposal.idempotencyKey())
                    .isEqualTo("risk:event-001:risk.limit-time-window");
            assertThat(proposal.payload())
                    .containsEntry("timezone", "Asia/Shanghai")
                    .containsEntry("allowedWindows", List.of("08:00-12:00", "13:00-17:00"));
        });
    }

    @Test
    void explicitBlockIpRequiresMatchingSafeShortLinkEvidence() {
        Fixture fixture = fixture();
        RiskManualActionDirective directive = new RiskManualActionDirective(
                RiskPolicyAction.BLOCK_IP,
                null,
                List.of()
        );
        List<ShortLinkRiskProfile> profiles = List.of(profile("abc123", 80, STRONG_REASONS));

        assertThat(fixture.factory().create(interactiveContext(
                directive,
                profiles,
                Map.of(TARGET, EVENT_ID),
                List.of()
        ))).isEmpty();

        List<AgentActionProposal> proposals = fixture.factory().create(interactiveContext(
                directive,
                profiles,
                Map.of(TARGET, EVENT_ID),
                List.of(shortLinkStatsExecution(TARGET))
        ));

        assertThat(proposals).singleElement().satisfies(proposal -> {
            assertThat(proposal.actionType()).isEqualTo(RiskPolicyActionTypes.BLOCK_IP);
            assertThat(proposal.targetKey()).isEqualTo(TARGET + "#" + IP_HASH);
            assertThat(proposal.activeSlotKey())
                    .isEqualTo("risk.block-ip:g1:" + TARGET + "#" + IP_HASH);
            assertThat(proposal.payload()).containsEntry("ipHash", IP_HASH);
            assertThat(proposal.evidence())
                    .containsEntry("maskedIp", "192.0.*.*")
                    .containsEntry("ipCount", 45L)
                    .doesNotContainKeys("ip", "rawIp");
            assertThat(proposal.toString()).doesNotContain("192.0.2.44");
        });
    }

    @Test
    void suppressionAndIncompleteInteractiveTargetsProduceNoProposal() {
        Fixture fixture = fixture();
        when(fixture.pendingActionService().isSuppressed(
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                TARGET
        )).thenReturn(true);
        RiskManualActionDirective disable = new RiskManualActionDirective(
                RiskPolicyAction.DISABLE_SHORT_LINK,
                null,
                List.of()
        );

        assertThat(fixture.factory().create(interactiveContext(
                disable,
                List.of(profile("abc123", 80, STRONG_REASONS)),
                Map.of(TARGET, EVENT_ID),
                List.of()
        ))).isEmpty();
        verify(fixture.pendingActionService()).isSuppressed(
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                TARGET
        );

        ShortLinkRiskProfile incomplete = profile("abc123", 80, STRONG_REASONS, "", TARGET);
        assertThat(fixture.factory().create(interactiveContext(
                disable,
                List.of(incomplete),
                Map.of(TARGET, EVENT_ID),
                List.of()
        ))).isEmpty();
    }

    private Fixture fixture() {
        AgentProperties properties = new AgentProperties();
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        when(service.isSuppressed(any(AgentActionType.class), anyString()))
                .thenReturn(false);
        RiskActionProposalFactory factory = new RiskActionProposalFactory(
                properties,
                service,
                new RiskPolicyActionPayloadValidator(),
                new RiskIpEvidenceExtractor()
        );
        return new Fixture(properties, service, factory);
    }

    private RiskActionProposalContext batchContext(
            List<ShortLinkRiskProfile> profiles,
            Map<String, String> eventIds
    ) {
        return context(
                "risk-profile:batch-1",
                profiles,
                eventIds,
                Optional.empty(),
                List.of()
        );
    }

    private RiskActionProposalContext interactiveContext(
            RiskManualActionDirective directive,
            List<ShortLinkRiskProfile> profiles,
            Map<String, String> eventIds,
            List<Map<String, Object>> executions
    ) {
        return context(
                "interactive:event-001",
                profiles,
                eventIds,
                Optional.of(directive),
                executions
        );
    }

    private RiskActionProposalContext context(
            String sourceId,
            List<ShortLinkRiskProfile> profiles,
            Map<String, String> eventIds,
            Optional<RiskManualActionDirective> directive,
            List<Map<String, Object>> executions
    ) {
        return new RiskActionProposalContext(
                sourceId,
                "g1",
                "session-1",
                "risk-analysis-worker",
                "trace-1",
                eventIds,
                profiles,
                List.of(),
                executions,
                directive
        );
    }

    private ShortLinkRiskProfile profile(
            String shortUri,
            int score,
            Set<RiskReasonCode> reasonCodes
    ) {
        return profile(shortUri, score, reasonCodes, "nurl.ink", "nurl.ink/" + shortUri);
    }

    private ShortLinkRiskProfile profile(
            String shortUri,
            int score,
            Set<RiskReasonCode> reasonCodes,
            String domain,
            String fullShortUrl
    ) {
        LocalDateTime end = LocalDateTime.of(2026, 7, 11, 10, 0);
        return new ShortLinkRiskProfile(
                "g1",
                domain,
                shortUri,
                fullShortUrl,
                end.minusHours(2),
                end,
                new ShortLinkRiskMetrics(
                        600, 50, 900, 300, 2100, 1200,
                        8.0, 0.82, 0.78, 0.50, 0.65, 0.60, 12.0, 0.74, 0.88
                ),
                score,
                score,
                RiskLevel.fromScore(score),
                reasonCodes,
                RiskWatchStatus.NONE,
                List.of(),
                "",
                "batch-1"
        );
    }

    private Map<String, Object> shortLinkStatsExecution(String target) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", "get_short_link_stats");
        execution.put("success", true);
        execution.put("arguments", Map.of("fullShortUrl", target));
        execution.put("data", Map.of("topIpStats", List.of(Map.of(
                "ipHash", IP_HASH,
                "maskedIp", "192.0.*.*",
                "cnt", 45
        ))));
        return execution;
    }

    private record Fixture(
            AgentProperties properties,
            AgentPendingActionService pendingActionService,
            RiskActionProposalFactory factory
    ) {
    }
}
