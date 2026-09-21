package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import java.util.Objects;
import java.util.Optional;

/**
 * Classifies an admitted historical release fact for a later recovery transport.
 *
 * <p>This class is deliberately a pure, transport-neutral hand-off boundary.  A caller must
 * first obtain a {@link CampaignStatisticsHistoricalReleaseRecoveryGate.Permit} from the E101
 * second-read gate.  This boundary only checks the trusted operation vocabulary and the permit's
 * expiry again; it does not turn the permit into a run token or an authorization grant.  It never
 * reads or writes a store, invokes a network/Graph/tool operation, or exposes request/payload
 * data.</p>
 */
public final class CampaignStatisticsHistoricalReleaseRecoveryAction {
    private CampaignStatisticsHistoricalReleaseRecoveryAction() {
    }

    /** The only transport-neutral operations that may be handed to a future executor. */
    public enum Operation {
        /** Reconcile status for the already-created physical job. */
        STATUS_RECONCILE,
        /** Release an already-created result; never submit a new job. */
        RELEASE_EXISTING_ONLY
    }

    /**
     * Minimal immutable identity for one already-created historical release operation.
     *
     * <p>Every field is copied from the exact E101 permit.  The source run version is retained as
     * a durable fencing fact (it is not a credential); request body, artifact payload, run token
     * and advance token remain intentionally absent.  This value is an advisory hand-off and must
     * be revalidated by the gate immediately before execution.</p>
     */
    public record ExistingOnlyAction(String releaseBindingId, String physicalBindingId,
                                     String runId, int revision, String childId, String jobId,
                                     long bindingVersion, long sourceRunVersion, long expiresAtMillis,
                                     Operation operation) {
        public ExistingOnlyAction {
            requireId(releaseBindingId, "HISTORICAL_RELEASE_ACTION_RELEASE_ID_INVALID", 96);
            requireId(physicalBindingId, "HISTORICAL_RELEASE_ACTION_PHYSICAL_ID_INVALID", 96);
            requireId(runId, "HISTORICAL_RELEASE_ACTION_RUN_INVALID", 96);
            if (revision < 1) {
                throw new IllegalArgumentException("HISTORICAL_RELEASE_ACTION_REVISION_INVALID");
            }
            requireId(childId, "HISTORICAL_RELEASE_ACTION_CHILD_INVALID", 96);
            requireId(jobId, "HISTORICAL_RELEASE_ACTION_JOB_INVALID", 128);
            if (bindingVersion < 1) {
                throw new IllegalArgumentException("HISTORICAL_RELEASE_ACTION_VERSION_INVALID");
            }
            if (sourceRunVersion < 0) {
                throw new IllegalArgumentException("HISTORICAL_RELEASE_ACTION_RUN_VERSION_INVALID");
            }
            if (expiresAtMillis < 1) {
                throw new IllegalArgumentException("HISTORICAL_RELEASE_ACTION_EXPIRY_INVALID");
            }
            Objects.requireNonNull(operation, "HISTORICAL_RELEASE_ACTION_OPERATION_REQUIRED");
        }

        private static void requireId(String value, String code, int max) {
            if (value == null || value.isBlank() || value.length() > max
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
                throw new IllegalArgumentException(code);
            }
        }
    }

    /**
     * Prepares a transport-neutral action from one E101 advisory permit.
     *
     * <p>An expired permit is an ordinary stale discovery fact and returns {@link Optional#empty}
     * so a caller cannot accidentally promote it.  Negative clock values and null trusted inputs
     * are programmer errors and fail closed with an explicit exception.</p>
     */
    public static Optional<ExistingOnlyAction> prepare(
            CampaignStatisticsHistoricalReleaseRecoveryGate.Permit permit,
            Operation operation,
            long nowMillis) {
        Objects.requireNonNull(permit, "HISTORICAL_RELEASE_ACTION_PERMIT_REQUIRED");
        Objects.requireNonNull(operation, "HISTORICAL_RELEASE_ACTION_OPERATION_REQUIRED");
        if (nowMillis < 0) {
            throw new IllegalArgumentException("HISTORICAL_RELEASE_ACTION_NOW_INVALID");
        }
        // Permit's constructor currently enforces localOnly=true.  Keep this check at the
        // hand-off boundary as a fail-closed fence if that contract ever broadens.
        if (!permit.localOnly() || permit.expiresAtMillis() <= nowMillis) {
            return Optional.empty();
        }
        return Optional.of(new ExistingOnlyAction(
                permit.releaseBindingId(), permit.physicalBindingId(), permit.runId(),
                permit.revision(), permit.childId(), permit.jobId(), permit.bindingVersion(),
                permit.sourceRunVersion(), permit.expiresAtMillis(), operation));
    }

    /** Descriptive alias for callers that name this step classification. */
    public static Optional<ExistingOnlyAction> classify(
            CampaignStatisticsHistoricalReleaseRecoveryGate.Permit permit,
            Operation operation,
            long nowMillis) {
        return prepare(permit, operation, nowMillis);
    }
}
