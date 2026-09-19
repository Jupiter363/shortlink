package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.ProgressSnapshot;
import java.util.List;

/** Internal projection of execution and registered receipts from one consistent ledger snapshot. */
@FunctionalInterface
public interface CampaignResultProgressReader {
    Snapshot read(Caller caller, String runId);

    record Snapshot(ProgressSnapshot execution, List<ReceiptProgress> receipts) {
        public Snapshot { receipts = List.copyOf(receipts); }
    }

    /** Scope and periods are authorization inputs only; never serialize this internal record to clients. */
    record ReceiptProgress(String stepId, String scopeRef, String periodsRef, long expiresAtMillis,
                           int receivedPages, int totalPages, long receivedRows, long totalRows) {}

    /** Checks current resource rights against the frozen run, without fabricating an Artifact. */
    @FunctionalInterface
    interface ScopeAuthorizer {
        boolean mayRead(Caller caller, RunDefinition run, String scopeRef, String periodsRef);
    }
}
