package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionViewEnricher;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskPolicyActionViewEnricherTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);
    private static final String POLICY_KEY =
            "risk:policy:short-link:rate-limit:nurl.ink:abc123";

    @Test
    void matchingEffectiveSlotOverridesPersistedSyncProjection() {
        JdbcEffectiveRiskPolicyRepository effectiveRepository =
                mock(JdbcEffectiveRiskPolicyRepository.class);
        JdbcRiskPolicyRepository policyRepository = mock(JdbcRiskPolicyRepository.class);
        when(effectiveRepository.findByPolicyKey(POLICY_KEY))
                .thenReturn(Optional.of(effective("policy-1", 1L, RiskPolicySyncStatus.SYNCED)));
        when(policyRepository.findByPolicyId("policy-1"))
                .thenReturn(Optional.of(history("policy-1", 1L, RiskPolicyStatus.ACTIVE)));
        RiskPolicyActionViewEnricher enricher = new RiskPolicyActionViewEnricher(
                effectiveRepository, policyRepository);

        Map<String, Object> result = enricher.enrich(null, persisted("policy-1", 1L));

        assertThat(result)
                .containsEntry("syncStatus", "SYNCED")
                .containsEntry("desiredState", "ACTIVE")
                .containsEntry("policyStatus", "ACTIVE")
                .containsEntry("effective", true);
    }

    @Test
    void newerEffectiveSlotDoesNotMasqueradeAsOldActionState() {
        JdbcEffectiveRiskPolicyRepository effectiveRepository =
                mock(JdbcEffectiveRiskPolicyRepository.class);
        JdbcRiskPolicyRepository policyRepository = mock(JdbcRiskPolicyRepository.class);
        when(effectiveRepository.findByPolicyKey(POLICY_KEY))
                .thenReturn(Optional.of(effective("policy-2", 2L, RiskPolicySyncStatus.SYNCED)));
        when(policyRepository.findByPolicyId("policy-1"))
                .thenReturn(Optional.of(history("policy-1", 1L, RiskPolicyStatus.SUPERSEDED)));
        RiskPolicyActionViewEnricher enricher = new RiskPolicyActionViewEnricher(
                effectiveRepository, policyRepository);

        Map<String, Object> result = enricher.enrich(null, persisted("policy-1", 1L));

        assertThat(result)
                .containsEntry("syncStatus", "PENDING")
                .containsEntry("policyStatus", "SUPERSEDED")
                .containsEntry("effective", false)
                .doesNotContainKey("desiredState");
    }

    private Map<String, Object> persisted(String policyId, long policyVersion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyKey", POLICY_KEY);
        result.put("policyId", policyId);
        result.put("policyVersion", policyVersion);
        result.put("syncStatus", "PENDING");
        return result;
    }

    private EffectiveRiskPolicy effective(
            String policyId,
            long policyVersion,
            RiskPolicySyncStatus syncStatus
    ) {
        return new EffectiveRiskPolicy(
                1L,
                POLICY_KEY,
                policyId,
                policyVersion,
                "gid-1",
                RiskPolicyAction.LIMIT_RATE,
                RiskPolicyDesiredState.ACTIVE,
                "{}",
                "{\"policyId\":\"" + policyId + "\"}",
                NOW.minusHours(1),
                null,
                syncStatus,
                "outbox-" + policyVersion,
                "trace-1",
                NOW.minusHours(1),
                NOW
        );
    }

    private RiskPolicy history(
            String policyId,
            long policyVersion,
            RiskPolicyStatus status
    ) {
        return RiskPolicy.shortLinkPolicy(
                policyId,
                POLICY_KEY,
                "manual:" + policyId,
                policyVersion,
                RiskPolicyAction.LIMIT_RATE,
                "gid-1",
                "nurl.ink",
                "abc123",
                "{}",
                RiskPolicySource.MANUAL_REVIEW,
                "trace-1",
                "event-1",
                NOW.minusHours(1),
                null
        ).withStatus(status);
    }
}
