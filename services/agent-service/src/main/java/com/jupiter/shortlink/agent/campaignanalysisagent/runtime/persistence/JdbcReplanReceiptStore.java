package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC implementation; recording is idempotent for a run revision. */
public final class JdbcReplanReceiptStore implements ReplanReceiptStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcReplanReceiptStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Receipt record(CampaignRunStore.RunToken run, int candidateRevision, String candidatePlanHash,
                          String requestJson, String decision, String reasonCode) {
        Objects.requireNonNull(run); Objects.requireNonNull(candidatePlanHash);
        Objects.requireNonNull(requestJson); Objects.requireNonNull(decision);
        validate(run, candidateRevision, candidatePlanHash, requestJson, decision, reasonCode);
        Instant now = clock.instant();
        return transactions.execute(status -> {
            verifyCurrent(run, true);
            Optional<Receipt> existing = read(run);
            if (existing.isPresent()) {
                Receipt value = existing.get();
                if (value.candidateRevision() != candidateRevision
                        || !value.candidatePlanHash().equals(candidatePlanHash)
                        || !value.requestJson().equals(requestJson)
                        || !value.decision().equals(decision)
                        || !Objects.equals(value.reasonCode(), reasonCode))
                    throw new IllegalStateException("REPLAN_RECEIPT_CONFLICT");
                return value;
            }
            String id = "replan-" + UUID.nameUUIDFromBytes((run.definition().runId() + ":"
                    + run.definition().revision()).getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO campaign_replan_receipt (receipt_id,run_id,base_revision,candidate_revision,candidate_plan_hash,request_json,decision,reason_code,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    id, run.definition().runId(), run.definition().revision(), candidateRevision, candidatePlanHash,
                    requestJson, decision, reasonCode, now.toEpochMilli());
            return read(run).orElseThrow(() -> new IllegalStateException("REPLAN_RECEIPT_NOT_FOUND"));
        });
    }

    @Override
    public Optional<Receipt> find(CampaignRunStore.RunToken run) {
        Objects.requireNonNull(run);
        return transactions.execute(status -> {
            verifyCurrent(run, false);
            return read(run);
        });
    }

    private Optional<Receipt> read(CampaignRunStore.RunToken run) {
        return jdbc.query("SELECT receipt_id,run_id,base_revision,candidate_revision,candidate_plan_hash,request_json,decision,reason_code,created_at FROM campaign_replan_receipt WHERE run_id=? AND base_revision=?",
                rs -> rs.next() ? Optional.of(read(rs)) : Optional.empty(), run.definition().runId(), run.definition().revision());
    }

    private void verifyCurrent(CampaignRunStore.RunToken run, boolean lock) {
        String sql = "SELECT tenant_id,subject_name,auth_version,run_status,row_version,advance_token FROM campaign_run_ledger WHERE run_id=? AND revision=?"
                + (lock ? " FOR UPDATE" : "");
        var rows = jdbc.queryForList(sql, run.definition().runId(), run.definition().revision());
        if (rows.size() != 1) throw new IllegalStateException("REPLAN_RUN_NOT_FOUND");
        var row = rows.get(0);
        if (!run.definition().caller().tenantId().equals(row.get("tenant_id"))
                || !run.definition().caller().subject().equals(row.get("subject_name"))
                || run.definition().caller().authVersion() != ((Number) row.get("auth_version")).longValue()
                || !"ACTIVE".equals(row.get("run_status"))
                || run.version() != ((Number) row.get("row_version")).longValue()
                || !run.advanceToken().equals(row.get("advance_token")))
            throw new SecurityException("REPLAN_RUN_TOKEN_INVALID");
    }

    private static void validate(CampaignRunStore.RunToken run, int candidateRevision, String candidatePlanHash,
                                 String requestJson, String decision, String reasonCode) {
        if (candidateRevision < 0 || run.definition().revision() < 1
                || "ACCEPTED".equals(decision) && candidateRevision <= run.definition().revision()
                || !candidatePlanHash.matches("[0-9a-fA-F]{64}") || requestJson.length() > 1_048_576
                || !decision.matches("ACCEPTED|REJECTED")
                || reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("REPLAN_RECEIPT_INVALID");
    }

    private static Receipt read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Receipt(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), Instant.ofEpochMilli(rs.getLong(9)));
    }
}
