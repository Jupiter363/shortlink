package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunRecord;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves one exact run ledger row. It never calls the latest-revision lookup, so a stale or
 * superseded response request cannot silently attach to another revision.
 */
public final class JdbcCampaignRunHandleResolver implements CampaignRunHandleResolver {
    private final JdbcCampaignRunStore runs;

    public JdbcCampaignRunHandleResolver(JdbcCampaignRunStore runs) {
        this.runs = Objects.requireNonNull(runs, "CAMPAIGN_RUN_STORE_REQUIRED");
    }

    @Override
    public Optional<CampaignRunHandle> resolve(Request request) {
        Objects.requireNonNull(request, "CAMPAIGN_RUN_HANDLE_REQUEST_REQUIRED");
        Optional<RunRecord> found = runs.readRunAtRevision(
                request.caller(), request.runId(), request.revision());
        if (found.isEmpty()) return Optional.empty();

        RunRecord record = found.get();
        RunDefinition definition = record.definition();
        if (definition == null || record.status() == null || record.version() < 0
                || record.advanceToken() == null || record.advanceToken().isBlank())
            throw new IllegalStateException("CAMPAIGN_RUN_HANDLE_CORRUPTED");
        if (!request.caller().equals(definition.caller())
                || !request.sessionId().equals(definition.sessionId())
                || !request.runId().equals(definition.runId())
                || request.revision() != definition.revision())
            throw new SecurityException("CAMPAIGN_RUN_HANDLE_BINDING_MISMATCH");
        if (!request.expectedPlanId().equals(definition.planId()))
            throw new SecurityException("CAMPAIGN_RUN_HANDLE_PLAN_MISMATCH");
        return Optional.of(new CampaignRunHandle(definition.caller(), definition.sessionId(),
                definition.runId(), definition.planId(), definition.revision(), record.status()));
    }
}
