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
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskActionProposalFactoryTest {

    private static final String TARGET = "nurl.ink/abc123";
    private static final String EVENT_ID = "event-001";
    private static final String IP_HASH = "a".repeat(64);
    private static final String DISABLE_POLICY_KEY =
            "risk:policy:short-link:disable:nurl.ink:abc123";
    private static final String TIME_WINDOW_POLICY_KEY =
            "risk:policy:short-link:time-window:nurl.ink:abc123";
    private static final String BLOCK_IP_POLICY_KEY =
            "risk:policy:short-link:block-ip:nurl.ink:abc123:" + IP_HASH;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 11, 10, 0);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(SHANGHAI).toInstant(), SHANGHAI);
    private static final Set<RiskReasonCode> STRONG_REASONS = Set.of(
            RiskReasonCode.TRAFFIC_SPIKE,
            RiskReasonCode.IP_CONCENTRATION,
            RiskReasonCode.HIGH_REPEAT_VISIT
    );

    @Test
    void productionFactoryIsAComponentWithOneAutowiredConstructor() {
        assertThat(RiskActionProposalFactory.class.isAnnotationPresent(Component.class)).isTrue();
        Constructor<?>[] constructors = RiskActionProposalFactory.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(List.of(constructors))
                .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .singleElement()
                .satisfies(constructor -> {
                    assertThat(constructor.getParameterTypes()).containsExactly(
                            AgentProperties.class,
                            AgentPendingActionService.class,
                            RiskPolicyActionPayloadValidator.class,
                            RiskIpEvidenceExtractor.class,
                            JdbcEffectiveRiskPolicyRepository.class,
                            Clock.class
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
        verifyNoInteractions(fixture.effectiveRepository());

        ShortLinkRiskProfile incomplete = profile("abc123", 80, STRONG_REASONS, "", TARGET);
        assertThat(fixture.factory().create(interactiveContext(
                disable,
                List.of(incomplete),
                Map.of(TARGET, EVENT_ID),
                List.of()
        ))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("activeEffectivePolicyCases")
    void activeUnexpiredEffectivePolicySuppressesMatchingPolicyKeyAfterRejectionCheck(
            EffectivePolicyCase policyCase,
            LocalDateTime expireTime
    ) {
        Fixture fixture = fixture();
        when(fixture.effectiveRepository().findByPolicyKey(policyCase.policyKey()))
                .thenReturn(Optional.of(effectivePolicy(
                        policyCase,
                        RiskPolicyDesiredState.ACTIVE,
                        expireTime
                )));

        List<AgentActionProposal> proposals = fixture.factory().create(interactiveContext(
                policyCase.directive(),
                List.of(profile("abc123", 80, STRONG_REASONS)),
                Map.of(TARGET, EVENT_ID),
                policyCase.toolExecutions()
        ));

        assertThat(proposals).isEmpty();
        var order = inOrder(fixture.pendingActionService(), fixture.effectiveRepository());
        order.verify(fixture.pendingActionService()).isSuppressed(
                policyCase.actionType(),
                policyCase.targetKey()
        );
        order.verify(fixture.effectiveRepository()).findByPolicyKey(policyCase.policyKey());
    }

    @ParameterizedTest
    @MethodSource("nonSuppressingEffectiveSlots")
    void inactiveOrExpiredEffectiveSlotAllowsNewProposal(
            RiskPolicyDesiredState desiredState,
            LocalDateTime expireTime
    ) {
        Fixture fixture = fixture();
        EffectivePolicyCase policyCase = disablePolicyCase();
        when(fixture.effectiveRepository().findByPolicyKey(policyCase.policyKey()))
                .thenReturn(Optional.of(effectivePolicy(policyCase, desiredState, expireTime)));

        List<AgentActionProposal> proposals = fixture.factory().create(interactiveContext(
                policyCase.directive(),
                List.of(profile("abc123", 80, STRONG_REASONS)),
                Map.of(TARGET, EVENT_ID),
                policyCase.toolExecutions()
        ));

        assertThat(proposals).singleElement();
        verify(fixture.effectiveRepository()).findByPolicyKey(policyCase.policyKey());
    }

    private Fixture fixture() {
        AgentProperties properties = new AgentProperties();
        AgentPendingActionService service = mock(AgentPendingActionService.class);
        JdbcEffectiveRiskPolicyRepository effectiveRepository =
                mock(JdbcEffectiveRiskPolicyRepository.class);
        when(service.isSuppressed(any(AgentActionType.class), anyString()))
                .thenReturn(false);
        RiskActionProposalFactory factory = new RiskActionProposalFactory(
                properties,
                service,
                new RiskPolicyActionPayloadValidator(),
                new RiskIpEvidenceExtractor(),
                effectiveRepository,
                CLOCK
        );
        return new Fixture(properties, service, effectiveRepository, factory);
    }

    private static Stream<Arguments> activeEffectivePolicyCases() {
        return Stream.concat(
                Stream.of(
                        disablePolicyCase(),
                        timeWindowPolicyCase(),
                        blockIpPolicyCase()
                ).map(policyCase -> Arguments.of(policyCase, NOW.plusHours(1))),
                Stream.of(Arguments.of(disablePolicyCase(), (LocalDateTime) null))
        );
    }

    private static Stream<Arguments> nonSuppressingEffectiveSlots() {
        return Stream.of(
                Arguments.of(RiskPolicyDesiredState.DISABLED, null),
                Arguments.of(RiskPolicyDesiredState.EXPIRED, null),
                Arguments.of(RiskPolicyDesiredState.ACTIVE, NOW),
                Arguments.of(RiskPolicyDesiredState.ACTIVE, NOW.minusSeconds(1))
        );
    }

    private static EffectivePolicyCase disablePolicyCase() {
        return new EffectivePolicyCase(
                new RiskManualActionDirective(
                        RiskPolicyAction.DISABLE_SHORT_LINK,
                        null,
                        List.of()
                ),
                List.of(),
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                TARGET,
                DISABLE_POLICY_KEY
        );
    }

    private static EffectivePolicyCase timeWindowPolicyCase() {
        return new EffectivePolicyCase(
                new RiskManualActionDirective(
                        RiskPolicyAction.LIMIT_TIME_WINDOW,
                        "Asia/Shanghai",
                        List.of("08:00-12:00")
                ),
                List.of(),
                RiskPolicyActionTypes.LIMIT_TIME_WINDOW,
                TARGET,
                TIME_WINDOW_POLICY_KEY
        );
    }

    private static EffectivePolicyCase blockIpPolicyCase() {
        return new EffectivePolicyCase(
                new RiskManualActionDirective(RiskPolicyAction.BLOCK_IP, null, List.of()),
                List.of(shortLinkStatsExecution(TARGET)),
                RiskPolicyActionTypes.BLOCK_IP,
                TARGET + "#" + IP_HASH,
                BLOCK_IP_POLICY_KEY
        );
    }

    private EffectiveRiskPolicy effectivePolicy(
            EffectivePolicyCase policyCase,
            RiskPolicyDesiredState desiredState,
            LocalDateTime expireTime
    ) {
        return new EffectiveRiskPolicy(
                1L,
                policyCase.policyKey(),
                "policy-effective-1",
                1L,
                "g1",
                policyCase.directive().action(),
                desiredState,
                "{}",
                "{}",
                NOW.minusHours(1),
                expireTime,
                RiskPolicySyncStatus.PENDING,
                "outbox-1",
                "trace-1",
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
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

    private static Map<String, Object> shortLinkStatsExecution(String target) {
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
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            RiskActionProposalFactory factory
    ) {
    }

    private record EffectivePolicyCase(
            RiskManualActionDirective directive,
            List<Map<String, Object>> toolExecutions,
            AgentActionType actionType,
            String targetKey,
            String policyKey
    ) {
    }
}
