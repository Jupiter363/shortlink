package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsHistoricalReleaseRecoveryAction.ExistingOnlyAction;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsHistoricalReleaseRecoveryAction.Operation;
import org.junit.jupiter.api.Test;

class CampaignStatisticsHistoricalReleaseRecoveryActionTest {
    private static final long NOW = 1_000L;
    private static final long EXPIRES = 2_000L;

    private static CampaignStatisticsHistoricalReleaseRecoveryGate.Permit permit() {
        return new CampaignStatisticsHistoricalReleaseRecoveryGate.Permit(
                "release-binding", "physical-binding", "run-1", 3, "child-1", "job-1",
                4L, 7L, EXPIRES, true);
    }

    @Test
    void copiesExactIdentityForEachTrustedOperation() {
        ExistingOnlyAction reconcile = CampaignStatisticsHistoricalReleaseRecoveryAction
                .prepare(permit(), Operation.STATUS_RECONCILE, NOW).orElseThrow();
        assertEquals("release-binding", reconcile.releaseBindingId());
        assertEquals("physical-binding", reconcile.physicalBindingId());
        assertEquals("run-1", reconcile.runId());
        assertEquals(3, reconcile.revision());
        assertEquals("child-1", reconcile.childId());
        assertEquals("job-1", reconcile.jobId());
        assertEquals(4L, reconcile.bindingVersion());
        assertEquals(7L, reconcile.sourceRunVersion());
        assertEquals(EXPIRES, reconcile.expiresAtMillis());
        assertEquals(Operation.STATUS_RECONCILE, reconcile.operation());

        ExistingOnlyAction release = CampaignStatisticsHistoricalReleaseRecoveryAction
                .classify(permit(), Operation.RELEASE_EXISTING_ONLY, NOW).orElseThrow();
        assertEquals(Operation.RELEASE_EXISTING_ONLY, release.operation());
        assertEquals(reconcile.releaseBindingId(), release.releaseBindingId());
        assertEquals(reconcile.physicalBindingId(), release.physicalBindingId());
    }

    @Test
    void refusesExpiredOrInvalidClockAndTrustedInputs() {
        assertTrue(CampaignStatisticsHistoricalReleaseRecoveryAction
                .prepare(permit(), Operation.STATUS_RECONCILE, EXPIRES).isEmpty());
        assertTrue(CampaignStatisticsHistoricalReleaseRecoveryAction
                .prepare(permit(), Operation.STATUS_RECONCILE, EXPIRES + 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CampaignStatisticsHistoricalReleaseRecoveryAction
                .prepare(permit(), Operation.STATUS_RECONCILE, -1));
        assertThrows(NullPointerException.class, () -> CampaignStatisticsHistoricalReleaseRecoveryAction
                .prepare(null, Operation.STATUS_RECONCILE, NOW));
        assertThrows(NullPointerException.class, () -> CampaignStatisticsHistoricalReleaseRecoveryAction
                .prepare(permit(), null, NOW));
    }

    @Test
    void outputIsAValidatedMinimalImmutableRecord() {
        ExistingOnlyAction action = CampaignStatisticsHistoricalReleaseRecoveryAction
                .prepare(permit(), Operation.RELEASE_EXISTING_ONLY, NOW).orElseThrow();
        assertEquals(action, new ExistingOnlyAction("release-binding", "physical-binding", "run-1",
                3, "child-1", "job-1", 4L, 7L, EXPIRES, Operation.RELEASE_EXISTING_ONLY));
        assertFalse(action.toString().contains("request"));
        assertFalse(action.toString().contains("payload"));
        assertThrows(IllegalArgumentException.class, () -> new ExistingOnlyAction(
                "bad id", "physical-binding", "run-1", 3, "child-1", "job-1", 4L,
                7L, EXPIRES, Operation.STATUS_RECONCILE));
        assertThrows(NullPointerException.class, () -> new ExistingOnlyAction(
                "release-binding", "physical-binding", "run-1", 3, "child-1", "job-1", 4L,
                7L, EXPIRES, null));
        assertThrows(IllegalArgumentException.class, () -> new ExistingOnlyAction(
                "release-binding", "physical-binding", "run-1", 3, "child-1", "job-1", 4L,
                -1L, EXPIRES, Operation.STATUS_RECONCILE));
    }
}
