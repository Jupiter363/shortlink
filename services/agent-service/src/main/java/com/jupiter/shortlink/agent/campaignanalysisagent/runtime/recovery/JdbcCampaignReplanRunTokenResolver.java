package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the current write token for a trusted replan request.
 *
 * <p>The lookup is deliberately delegated to the JDBC run ledger's owner-bound latest-row read.
 * The resolver does not cache tokens, select a run by session, or manufacture a token.  The
 * runtime factory performs a second persisted token/status check after this read, so a cancel,
 * revision, or advance racing the resolver is rejected before a receipt or revision is written.</p>
 */
public final class JdbcCampaignReplanRunTokenResolver
        implements CampaignReplanTrustedAdapter.RunTokenResolver {
    private final JdbcCampaignRunStore runs;

    public JdbcCampaignReplanRunTokenResolver(JdbcCampaignRunStore runs) {
        this.runs = Objects.requireNonNull(runs, "REPLAN_RUN_STORE_REQUIRED");
    }

    @Override
    public Optional<RunToken> resolve(Caller owner, String sessionId, String runId) {
        validateOwner(owner);
        requireId(sessionId, "REPLAN_SESSION_REQUIRED");
        requireId(runId, "REPLAN_RUN_ID_REQUIRED");

        // JdbcCampaignRunStore.loadRun selects the newest revision for this exact run id and
        // checks the persisted caller before returning.  Never retain this value between calls.
        Optional<RunRecord> found = runs.loadRun(owner, runId);
        if (found == null || found.isEmpty()) return Optional.empty();

        RunRecord record = found.get();
        if (record == null) throw new IllegalStateException("REPLAN_RUN_TOKEN_CORRUPTED");
        RunDefinition definition = record.definition();
        if (definition == null || definition.caller() == null || definition.revision() < 1
                || record.status() == null || record.version() < 0
                || record.advanceToken() == null || record.advanceToken().isBlank()) {
            throw new IllegalStateException("REPLAN_RUN_TOKEN_CORRUPTED");
        }

        // Keep this check even though the JDBC store currently enforces owner equality.  It is
        // the contract boundary if the store is later wrapped or replaced by another JDBC view.
        if (!owner.equals(definition.caller()) || !runId.equals(definition.runId())) {
            throw new SecurityException("REPLAN_RUN_TOKEN_BINDING_MISMATCH");
        }
        // A session is transport-owned context, not a query key.  A mismatch is intentionally
        // indistinguishable from a missing token to avoid disclosing another session's run.
        if (!sessionId.equals(definition.sessionId())) return Optional.empty();
        if (record.status() != RunStatus.ACTIVE) return Optional.empty();

        return Optional.of(record.token());
    }

    private static void validateOwner(Caller owner) {
        if (owner == null) throw new IllegalArgumentException("REPLAN_OWNER_REQUIRED");
        requireId(owner.tenantId(), "REPLAN_OWNER_INVALID");
        if (owner.subject() == null || owner.subject().isBlank() || owner.subject().length() > 128
                || owner.subject().chars().anyMatch(Character::isISOControl) || owner.authVersion() < 1) {
            throw new IllegalArgumentException("REPLAN_OWNER_INVALID");
        }
    }

    private static void requireId(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 96
                || value.chars().anyMatch(Character::isISOControl)
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
            throw new IllegalArgumentException(code);
        }
    }
}
