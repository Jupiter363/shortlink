package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import java.util.List;

/**
 * Read-only discovery of release intents whose producer revision was retired by a replan.
 *
 * <p>The result is an advisory fact for a future historical-release coordinator.  It is not an
 * authorization grant: no run token, advance token, request body, artifact payload or remote
 * operation is exposed by this boundary.</p>
 */
public interface CampaignStatisticsHistoricalReleaseRecoveryReader {
    /** A minimal, immutable fact about one retired producer release intent. */
    record RetiredReleaseCandidate(String runId, int revision, String childId, String jobId,
                                   long bindingVersion, long expiresAtMillis,
                                   long sourceRunVersion, int activeConsumerCount,
                                   boolean localOnly) {
        public RetiredReleaseCandidate {
            requireId(runId, "HISTORICAL_RELEASE_RUN_INVALID", 96);
            if (revision < 1) throw new IllegalArgumentException("HISTORICAL_RELEASE_REVISION_INVALID");
            requireId(childId, "HISTORICAL_RELEASE_CHILD_INVALID", 96);
            requireId(jobId, "HISTORICAL_RELEASE_JOB_INVALID", 128);
            if (bindingVersion < 1) throw new IllegalArgumentException("HISTORICAL_RELEASE_VERSION_INVALID");
            if (expiresAtMillis < 1) throw new IllegalArgumentException("HISTORICAL_RELEASE_EXPIRY_INVALID");
            if (sourceRunVersion < 0) throw new IllegalArgumentException("HISTORICAL_RELEASE_RUN_VERSION_INVALID");
            if (activeConsumerCount < 0) throw new IllegalArgumentException("HISTORICAL_RELEASE_CONSUMER_COUNT_INVALID");
        }

        private static void requireId(String value, String code, int max) {
            if (value == null || value.isBlank() || value.length() > max
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException(code);
        }
    }

    /**
     * Returns at most {@code limit} unexpired retired-producer candidates for one caller.
     * Implementations must not claim, mutate, issue remote calls or return execution credentials.
     */
    List<RetiredReleaseCandidate> pendingRetired(Caller caller, long nowMillis, int limit);

    /** Descriptive alias for callers that name the operation by its historical purpose. */
    default List<RetiredReleaseCandidate> pendingHistorical(Caller caller, long nowMillis, int limit) {
        return pendingRetired(caller, nowMillis, limit);
    }
}
