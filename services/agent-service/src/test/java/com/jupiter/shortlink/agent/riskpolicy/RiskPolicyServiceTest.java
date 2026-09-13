package com.jupiter.shortlink.agent.riskpolicy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.riskpolicy.service.*;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class RiskPolicyServiceTest {
    private final CommandPolicyClient commands = mock(CommandPolicyClient.class);
    private final AgentProperties properties = new AgentProperties();
    private final RiskPolicyService service =
            new RiskPolicyService(
                    commands,
                    properties,
                    Clock.fixed(Instant.ofEpochMilli(StatsTestFixtures.NOW), ZoneOffset.UTC));

    @org.junit.jupiter.api.BeforeEach
    void absentCommands() {
        when(commands.result(any(), anyString())).thenReturn(null);
    }

    @Test
    void authorityReadReturningAfterCancellationCannotDispatchANewCommand() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(commands.current(StatsTestFixtures.PRINCIPAL, List.of(99L))).thenAnswer(invocation -> {
            cancelled.set(true);
            return List.of(Map.of("resourceKey", "1001:99", "state", "KNOWN_ALLOWED", "policyRevision", 7L,
                    "evaluatedAt", StatsTestFixtures.NOW, "validUntil", StatsTestFixtures.NOW + 1000));
        });
        assertThatThrownBy(() -> service.autoLimitRate(StatsTestFixtures.PRINCIPAL, StatsTestFixtures.profile(),
                "late-cmd", "late-policy", () -> {
                    if (cancelled.get()) throw new IllegalStateException("execution cancelled");
                })).hasMessage("execution cancelled");
        verify(commands).current(StatsTestFixtures.PRINCIPAL, List.of(99L));
        verify(commands, never()).activate(any(), any());
    }

    @Test
    void committedCommandIsReadBeforeExpiredEvidenceAndDoesNotWriteAgain() {
        var receipt =
                Map.<String, Object>of(
                        "commandId", "cmd-1", "status", "COMMITTED", "policyRevision", 4L);
        when(commands.result(StatsTestFixtures.PRINCIPAL, "cmd-1")).thenReturn(receipt);
        assertThat(
                        service.autoLimitRate(
                                StatsTestFixtures.PRINCIPAL,
                                StatsTestFixtures.profile().withEvidence(null),
                                "cmd-1",
                                "policy-1"))
                .isEqualTo(receipt);
        verify(commands, never()).current(any(), any());
        verify(commands, never()).activate(any(), any());
    }

    @Test
    void newCommandIncludesImmutableEvidenceStableLinkAndExpectedRevision() {
        when(commands.current(StatsTestFixtures.PRINCIPAL, List.of(99L)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "resourceKey",
                                        "1001:99",
                                        "state",
                                        "KNOWN_ALLOWED",
                                        "policyRevision",
                                        7L,
                                        "evaluatedAt",
                                        StatsTestFixtures.NOW,
                                        "validUntil",
                                        StatsTestFixtures.NOW + 1000)));
        when(commands.activate(eq(StatsTestFixtures.PRINCIPAL), any()))
                .thenAnswer(
                        call ->
                                Map.of(
                                        "status",
                                        "COMMITTED",
                                        "commandId",
                                        ((Map<?, ?>) call.getArgument(1)).get("commandId")));
        assertThat(
                        service.autoLimitRate(
                                StatsTestFixtures.PRINCIPAL,
                                StatsTestFixtures.profile(),
                                "cmd-1",
                                "policy-1"))
                .containsEntry("status", "COMMITTED");
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(commands).activate(eq(StatsTestFixtures.PRINCIPAL), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("linkId", 99L)
                .containsEntry("expectedPolicyRevision", 7L)
                .containsEntry("automatic", true);
        assertThat((Map<String, Object>) captor.getValue().get("evidence"))
                .containsEntry("snapshotId", "snapshot-1")
                .containsEntry("recoveryEpoch", "epoch-1")
                .containsEntry("executeBefore", StatsTestFixtures.NOW + 120_000);
    }

    @Test
    void unknownQualityAndMissingPrincipalCannotCauseMutation() {
        var meta = StatsTestFixtures.meta();
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        var profile =
                StatsTestFixtures.profile()
                        .withEvidence(
                                new StatsEvidence(
                                        "1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION));
        assertThat(service.autoLimitRate(StatsTestFixtures.PRINCIPAL, profile, "cmd-1", "policy-1"))
                .containsEntry("status", "EVIDENCE_UNAVAILABLE");
        assertThatThrownBy(() -> service.autoLimitRate(null, profile, "cmd-2", "policy-1"))
                .isInstanceOf(SecurityException.class);
        verify(commands, never()).activate(any(), any());
        verify(commands, never()).current(any(), any());
    }

    @Test
    void currentSnapshotCannotChangeTenantOrResource() {
        when(commands.current(StatsTestFixtures.PRINCIPAL, List.of(99L)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "resourceKey",
                                        "2002:99",
                                        "state",
                                        "KNOWN_ALLOWED",
                                        "policyRevision",
                                        7)));
        assertThatThrownBy(
                        () ->
                                service.autoLimitRate(
                                        StatsTestFixtures.PRINCIPAL,
                                        StatsTestFixtures.profile(),
                                        "cmd-1",
                                        "policy-1"))
                .isInstanceOf(SecurityException.class);
        verify(commands, never()).activate(any(), any());
    }

    @Test
    void conflictAndExpiredReceiptsAreNotPromotedToActive() {
        when(commands.result(StatsTestFixtures.PRINCIPAL, "cmd-1"))
                .thenReturn(Map.of("status", "CONFLICT"));
        assertThat(
                        service.autoLimitRate(
                                StatsTestFixtures.PRINCIPAL,
                                StatsTestFixtures.profile(),
                                "cmd-1",
                                "policy-1"))
                .containsEntry("status", "CONFLICT");
        verify(commands, never()).activate(any(), any());
    }

    @Test
    void legacyMutationEntryPointsFailClosed() {
        assertThatThrownBy(() -> service.activatePolicy(null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.disablePolicy(null)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(commands);
    }

    @Test
    void approximateUvDoesNotBlockTwoExactPvRulesButApproximateIpCannotQualify() {
        var meta = StatsTestFixtures.meta();
        meta.put(
                "approximation",
                Map.of(
                        "pv",
                        Map.of("type", "EXACT"),
                        "uv",
                        Map.of("type", "APPROXIMATE"),
                        "peakHourShare",
                        Map.of("type", "EXACT"),
                        "topIpShare",
                        Map.of("type", "APPROXIMATE")));
        var original = StatsTestFixtures.profile();
        var evidence = new StatsEvidence("1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION);
        assertThat(
                        service.autoLimitRate(
                                StatsTestFixtures.PRINCIPAL,
                                original.withEvidence(evidence),
                                "denied",
                                "p"))
                .containsEntry("status", "EVIDENCE_UNAVAILABLE");
        var profile =
                new com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile(
                        original.gid(),
                        original.domain(),
                        original.shortUri(),
                        original.fullShortUrl(),
                        original.profileWindowStart(),
                        original.profileWindowEnd(),
                        original.metrics(),
                        original.anomalyScore(),
                        original.riskScore(),
                        original.riskLevel(),
                        Set.of(
                                com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode
                                        .TRAFFIC_SPIKE,
                                com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode
                                        .PEAK_HOUR_BURST),
                        original.watchStatus(),
                        original.latestPolicyActions(),
                        original.latestAgentSummary(),
                        original.batchId(),
                        evidence);
        when(commands.current(any(), any()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "resourceKey",
                                        "1001:99",
                                        "state",
                                        "KNOWN_ALLOWED",
                                        "policyRevision",
                                        7,
                                        "evaluatedAt",
                                        StatsTestFixtures.NOW,
                                        "validUntil",
                                        StatsTestFixtures.NOW + 1_000)));
        when(commands.activate(any(), any())).thenReturn(Map.of("status", "COMMITTED"));
        assertThat(service.autoLimitRate(StatsTestFixtures.PRINCIPAL, profile, "allowed", "p"))
                .containsEntry("status", "COMMITTED");
        verify(commands, times(1)).activate(any(), any());
    }
}
