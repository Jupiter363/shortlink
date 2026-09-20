package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.DispatchPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.List;
import java.util.Optional;

/**
 * Single-producer release intent, never a remote job engine. A REQUESTED binding promises only
 * its verified local artifact; shared consumers use the same physical job gate and can no longer
 * depend on remote pages once that promise commits. Legacy schemas do not enable shared consumers.
 * No HTTP, remote cancellation or GC runs here.
 */
public interface CampaignStatisticsReleaseStore {
    enum State { REQUESTED, CONFIRMED }

    /**
     * Small, read-only recovery index entry. It intentionally excludes request bodies, artifact
     * hashes/payloads, report credentials and run tokens; a trusted caller must resolve those
     * facts again before attempting any release operation.
     */
    record PendingIntent(String runId, int revision, String childId, String jobId,
                         long bindingVersion, long expiresAtMillis, State state) {
        public PendingIntent {
            requireId(runId, "RELEASE_PENDING_RUN_INVALID");
            if (revision < 1) throw new IllegalArgumentException("RELEASE_PENDING_REVISION_INVALID");
            requireId(childId, "RELEASE_PENDING_CHILD_INVALID");
            requireId(jobId, "RELEASE_PENDING_JOB_INVALID");
            if (bindingVersion < 1) throw new IllegalArgumentException("RELEASE_PENDING_VERSION_INVALID");
            if (expiresAtMillis < 1) throw new IllegalArgumentException("RELEASE_PENDING_EXPIRY_INVALID");
            if (state != State.REQUESTED) throw new IllegalArgumentException("RELEASE_PENDING_STATE_INVALID");
        }

        private static void requireId(String value, String code) {
            if (value == null || value.isBlank() || value.length() > 128
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException(code);
        }
    }

    /** version identifies the immutable binding; confirmation changes only state, not version. */
    record Intent(String bindingId, String producerRunId, int revision, String childId, String jobId,
                  String requestId, String requestHash, String artifactId, String artifactHash,
                  String chainHash, long expiresAtMillis, long version, State state) { }

    /** authorizer must be a pure current grant, with no remote I/O while the local locks are held. */
    Intent prepare(RunToken token, String childId, ArtifactAuthorizer authorizer);

    /** Reads historical release facts for the exact frozen definition and owner, including cancellation. */
    Optional<Intent> intent(RunToken token, String childId);

    /**
     * Returns a bounded, deterministic read-only index of currently actionable REQUESTED intents
     * for one caller. Only ACTIVE producer runs are included; no claim, token or network action is
     * performed. Callers must re-resolve the exact run and re-check all release gates before I/O.
     */
    List<PendingIntent> pendingRequested(Caller caller, int limit);

    /** Caller separately repeats its live authority gate immediately before each real HTTP operation. */
    boolean mayRelease(DispatchPermit permit, Intent intent);

    /** Exact RELEASE callback facts may arrive after cancellation; a replaced attempt cannot confirm. */
    void confirm(DispatchPermit permit, Intent intent, long remoteExpiresAt);
}
