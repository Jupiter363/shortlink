package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Limits;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.ReceiptSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backend-only joined progress read. The execution snapshot and receipt counters share one
 * transaction and current row locks. Page payloads, source snapshots, metrics and wire bodies
 * are deliberately absent from the receipt query.
 */
public final class JdbcCampaignResultProgressReader implements CampaignResultProgressReader {
    private static final int PAGE_SIZE = 500;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Limits limits;
    private final JdbcCampaignStepStore steps;

    public JdbcCampaignResultProgressReader(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this(jdbc, transactions, clock, Limits.defaults());
    }

    public JdbcCampaignResultProgressReader(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, Limits limits) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.limits = Objects.requireNonNull(limits);
        // The composed store enforces writable REQUIRED semantics and the exact same DataSource.
        this.steps = new JdbcCampaignStepStore(jdbc, transactions, Objects.requireNonNull(clock), limits);
    }

    /** Composition guard for a trusted outer coordinator. */
    public boolean sharesDataSource(JdbcTemplate other) {
        return other != null && other.getDataSource() == jdbc.getDataSource();
    }

    /** Composition guard: the coordinator must use this exact transaction template. */
    public boolean usesTransactionTemplate(TransactionTemplate other) {
        return transactions == other;
    }

    @Override
    public Snapshot read(Caller caller, String runId) {
        return transactions.execute(status -> readInCurrentTransaction(caller, runId));
    }

    /** Reads execution and receipt rows in the caller's already-open transaction. */
    Snapshot readInCurrentTransaction(Caller caller, String runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("PROGRESS_TRANSACTION_REQUIRED");
        var execution = steps.snapshotInCurrentTransaction(caller, runId);
        return readReceiptsInCurrentTransaction(execution, runId);
    }

    /** Reads one exact revision in the caller's already-open transaction. */
    Snapshot readInCurrentTransaction(Caller caller, String runId, int revision) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("PROGRESS_TRANSACTION_REQUIRED");
        if (revision < 1) throw new IllegalArgumentException("RUN_REVISION_INVALID");
        var execution = steps.snapshotInCurrentTransaction(caller, runId, revision);
        if (execution.run().definition().revision() != revision)
            throw new IllegalStateException("PROGRESS_SNAPSHOT_CHANGED");
        return readReceiptsInCurrentTransaction(execution, runId);
    }

    private Snapshot readReceiptsInCurrentTransaction(CampaignStepStore.ProgressSnapshot execution, String runId) {
        Set<String> registeredSteps = execution.steps().stream().map(step -> step.spec().stepId()).collect(Collectors.toSet());
        int revision = execution.run().definition().revision();
        // Start at the receipt and retain every row, including a malformed association. An
        // inner join to steps would silently hide an already-registered orphan receipt.
        var receipts = jdbc.query("SELECT a.step_id,r.spec_json,r.spec_hash,r.stored_pages,r.stored_rows "
                        + "FROM campaign_statistics_receipt r "
                        + "LEFT JOIN campaign_child_ledger c ON c.run_id=r.run_id AND c.revision=r.revision AND c.child_id=r.child_id "
                        + "LEFT JOIN campaign_action_ledger a ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                        + "LEFT JOIN campaign_step_ledger s ON s.run_id=a.run_id AND s.revision=a.revision AND s.step_id=a.step_id "
                        + "WHERE r.run_id=? AND r.revision=? ORDER BY s.ordinal_index,c.child_id FOR UPDATE",
                (rs, row) -> receipt(rs, registeredSteps), runId, revision);
        return new Snapshot(execution, receipts);
    }

    private ReceiptProgress receipt(ResultSet row, Set<String> registeredSteps) throws SQLException {
        String stepId = row.getString("step_id");
        if (stepId == null || !registeredSteps.contains(stepId)) fail("STATISTICS_PROGRESS_STEP_MISSING");
        String specJson = row.getString("spec_json");
        bounded(specJson);
        if (!CampaignRunStore.sha256(specJson).equals(row.getString("spec_hash"))) fail("STATISTICS_PROGRESS_RECEIPT_CORRUPTED");
        ReceiptSpec spec;
        try { spec = JSON.readValue(specJson, ReceiptSpec.class); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("STATISTICS_PROGRESS_RECEIPT_CORRUPTED"); }
        if (spec == null || !validId(spec.jobId(), 128) || !validId(spec.artifactId(), 96)
                || !validText(spec.scopeRef(), 256) || !validText(spec.periodsRef(), 256)
                || spec.requestHash() == null || !spec.requestHash().matches("[a-f0-9]{64}")
                || spec.totalRows() < 0 || spec.pageCount() < 0 || spec.expiresAtMillis() <= 0)
            fail("STATISTICS_PROGRESS_RECEIPT_CORRUPTED");
        long expectedPages = spec.totalRows() / PAGE_SIZE + (spec.totalRows() % PAGE_SIZE == 0 ? 0 : 1);
        if (expectedPages != spec.pageCount()) fail("STATISTICS_PROGRESS_RECEIPT_CORRUPTED");
        int totalResponses = Math.max(1, spec.pageCount());
        int receivedPages = row.getInt("stored_pages");
        long receivedRows = row.getLong("stored_rows");
        if (receivedPages < 0 || receivedPages > totalResponses || receivedRows < 0
                || receivedRows != Math.min(spec.totalRows(), (long) receivedPages * PAGE_SIZE))
            fail("STATISTICS_PROGRESS_COUNTS_CORRUPTED");
        return new ReceiptProgress(stepId, spec.scopeRef(), spec.periodsRef(), spec.expiresAtMillis(),
                receivedPages, totalResponses, receivedRows, spec.totalRows());
    }

    /** Check UTF-8 bytes before parsing, without allocating another whole JSON byte array. */
    private void bounded(String value) {
        if (value == null) fail("STATISTICS_PROGRESS_RECEIPT_CORRUPTED");
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4; i++;
            } else bytes += Character.isSurrogate(c) ? 1 : 3;
            if (bytes > limits.definitionBytes()) fail("STATISTICS_PROGRESS_SPEC_TOO_LARGE");
        }
    }

    private static boolean validId(String value, int maximum) {
        return validText(value, maximum) && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    private static boolean validText(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static void fail(String reason) { throw new IllegalStateException(reason); }
}
