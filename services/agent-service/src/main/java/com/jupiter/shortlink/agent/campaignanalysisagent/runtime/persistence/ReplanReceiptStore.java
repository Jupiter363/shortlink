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
}
