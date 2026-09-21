package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsHistoricalReleaseRecoveryReader.RetiredReleaseCandidate;
import java.util.Optional;

/**
 * Revalidates one historical-release discovery fact immediately before recovery work.
 *
 * <p>This is a read-only, transport-neutral fence.  The returned permit carries only durable
 * identity and version facts; it is not a run token, a remote authorization, or a release-state
 * mutation grant.</p>
 */
public interface CampaignStatisticsHistoricalReleaseRecoveryGate {
    /** Minimal immutable identity returned after a successful second read. */
    record Permit(String releaseBindingId, String physicalBindingId, String runId, int revision,
                  String childId, String jobId, long bindingVersion, long sourceRunVersion,
                  long expiresAtMillis, boolean localOnly) {
        public Permit {
            requireId(releaseBindingId, "HISTORICAL_RELEASE_GATE_RELEASE_ID_INVALID", 96);
            requireId(physicalBindingId, "HISTORICAL_RELEASE_GATE_PHYSICAL_ID_INVALID", 96);
            requireId(runId, "HISTORICAL_RELEASE_GATE_RUN_INVALID", 96);
            if (revision < 1) throw new IllegalArgumentException("HISTORICAL_RELEASE_GATE_REVISION_INVALID");
            requireId(childId, "HISTORICAL_RELEASE_GATE_CHILD_INVALID", 96);
            requireId(jobId, "HISTORICAL_RELEASE_GATE_JOB_INVALID", 128);
            if (bindingVersion < 1) throw new IllegalArgumentException("HISTORICAL_RELEASE_GATE_VERSION_INVALID");
            if (sourceRunVersion < 0) throw new IllegalArgumentException("HISTORICAL_RELEASE_GATE_RUN_VERSION_INVALID");
            if (expiresAtMillis < 1) throw new IllegalArgumentException("HISTORICAL_RELEASE_GATE_EXPIRY_INVALID");
            if (!localOnly) throw new IllegalArgumentException("HISTORICAL_RELEASE_GATE_LOCAL_ONLY_REQUIRED");
        }

        private static void requireId(String value, String code, int max) {
            if (value == null || value.isBlank() || value.length() > max
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException(code);
        }
    }

    /**
     * Re-reads and locks the exact source facts in one REQUIRED transaction.
     *
     * <p>{@link Optional#empty()} means the discovery fact is no longer admissible (for example
     * expiry, a callback, an active consumer, or a normal state/version change).  Corrupt linked
     * rows fail closed with an explicit {@code HISTORICAL_RELEASE_GATE_CORRUPTED} exception.</p>
     */
    Optional<Permit> revalidate(Caller caller, RetiredReleaseCandidate candidate, long nowMillis);

    /** Descriptive alias for callers that use admission terminology. */
    default Optional<Permit> admit(Caller caller, RetiredReleaseCandidate candidate, long nowMillis) {
        return revalidate(caller, candidate, nowMillis);
    }
}
