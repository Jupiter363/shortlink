package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.CapacityKind;
import java.util.List;

/**
 * Read-only discovery of durable capacity refusals that are eligible for a later exact resume.
 *
 * <p>A candidate is only a bounded hint.  It deliberately contains no writer token, request
 * body, artifact or remote job identity.  A trusted caller must resolve the exact run revision
 * again and re-check the run and child fences before refreshing a step or dispatching anything.</p>
 */
public interface CampaignSubmissionDeferralReader {
    /**
     * Small owner-scoped index row.  {@code sourceRunVersion} is an observation for stale-read
     * detection; it is not a write credential and may legitimately be zero for a newly-created
     * run.
     */
    record DueCandidate(String runId, int revision, String stepId, String childId,
                        CapacityKind capacityKind, int rejectedAttempts,
                        long retryNotBeforeMillis, long sourceRunVersion) {
        public DueCandidate {
            requireId(runId, "CAPACITY_PENDING_RUN_INVALID");
            if (revision < 1) throw new IllegalArgumentException("CAPACITY_PENDING_REVISION_INVALID");
            requireId(stepId, "CAPACITY_PENDING_STEP_INVALID");
            requireId(childId, "CAPACITY_PENDING_CHILD_INVALID");
            if (capacityKind == null) throw new IllegalArgumentException("CAPACITY_PENDING_KIND_INVALID");
            if (rejectedAttempts < 1) throw new IllegalArgumentException("CAPACITY_PENDING_COUNT_INVALID");
            if (retryNotBeforeMillis < 1) throw new IllegalArgumentException("CAPACITY_PENDING_DEADLINE_INVALID");
            if (sourceRunVersion < 0) throw new IllegalArgumentException("CAPACITY_PENDING_VERSION_INVALID");
        }

        private static void requireId(String value, String code) {
            if (value == null || value.isBlank() || value.length() > 96
                    || value.chars().anyMatch(Character::isISOControl)
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException(code);
        }
    }

    /**
     * Returns at most {@code limit} due candidates for one authenticated caller.  The read has no
     * claim, lease, writer acquisition, dispatch, remote I/O or mutation side effect.
     */
    List<DueCandidate> pendingDue(Caller caller, long nowMillis, int limit);
}
