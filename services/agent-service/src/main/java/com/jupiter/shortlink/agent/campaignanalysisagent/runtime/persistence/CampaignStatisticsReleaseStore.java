package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.DispatchPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.Optional;

/**
 * Single-producer release intent, never a remote job engine. A REQUESTED binding promises only
 * its verified local artifact; shared consumers use the same physical job gate and can no longer
 * depend on remote pages once that promise commits. Legacy schemas do not enable shared consumers.
 * No HTTP, remote cancellation or GC runs here.
 */
public interface CampaignStatisticsReleaseStore {
    enum State { REQUESTED, CONFIRMED }

    /** version identifies the immutable binding; confirmation changes only state, not version. */
    record Intent(String bindingId, String producerRunId, int revision, String childId, String jobId,
                  String requestId, String requestHash, String artifactId, String artifactHash,
                  String chainHash, long expiresAtMillis, long version, State state) { }

    /** authorizer must be a pure current grant, with no remote I/O while the local locks are held. */
    Intent prepare(RunToken token, String childId, ArtifactAuthorizer authorizer);

    /** Reads historical release facts for the exact frozen definition and owner, including cancellation. */
    Optional<Intent> intent(RunToken token, String childId);

    /** Caller separately repeats its live authority gate immediately before each real HTTP operation. */
    boolean mayRelease(DispatchPermit permit, Intent intent);

    /** Exact RELEASE callback facts may arrive after cancellation; a replaced attempt cannot confirm. */
    void confirm(DispatchPermit permit, Intent intent, long remoteExpiresAt);
}
