package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import java.time.Instant;
import java.util.Optional;

/** Durable acknowledgement of a replan decision, keyed by the run revision. */
public interface ReplanReceiptStore {
    record Receipt(String receiptId, String runId, int baseRevision, int candidateRevision,
                   String candidatePlanHash, String requestJson, String decision,
                   String reasonCode, Instant createdAt) {}

    Receipt record(CampaignRunStore.RunToken run, int candidateRevision, String candidatePlanHash,
                   String requestJson, String decision, String reasonCode);

    Optional<Receipt> find(CampaignRunStore.RunToken run);

    /**
     * Looks up a receipt after its base revision has been superseded. The caller identity is
     * checked by the durable implementation; this method intentionally does not require an
     * ACTIVE run token because an accepted replan retires that token as part of its commit.
     */
    default Optional<Receipt> findFinalized(CampaignRunStore.Caller caller, String runId, int baseRevision) {
        return Optional.empty();
    }
}
