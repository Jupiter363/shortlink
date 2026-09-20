package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC implementation. All publication, reference and cleanup mutations share one database transaction. */
public final class JdbcReportLifecycleStore implements ReportLifecycleStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcReportLifecycleStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Report lifecycle requires one writable REQUIRED DataSource transaction");
    }

    /** Composition guard for a trusted outer transaction; no Spring registration is implied. */
    public boolean sharesDataSource(JdbcTemplate other) {
        return other != null && other.getDataSource() == jdbc.getDataSource();
    }

    @Override
    public Published publish(Draft draft) {
        Objects.requireNonNull(draft);
        return tx(() -> {
            long now = clock.millis();
            if (draft.evidenceRetainedUntil().toEpochMilli() <= now || draft.retainedUntil().toEpochMilli() <= now)
                fail("REPORT_EVIDENCE_EXPIRED");
            var existing = find(draft.key(), true);
            if (existing.isPresent()) {
                Published previous = existing.get();
                if (same(previous, draft)) return previous;
                fail("REPORT_DRAFT_CONFLICT");
            }
            jdbc.update("INSERT INTO campaign_report_lifecycle (report_id,revision,run_id,plan_revision,owner_name,capability,"
                            + "manifest_json,manifest_checksum,evidence_retained_until,retained_until,reuse_expires_at,status,payload_json,"
                            + "row_version,reference_count,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,'READY',?,1,0,?,?)",
                    draft.key().reportId(), draft.key().revision(), draft.runId(), draft.planRevision(), draft.owner(), draft.capability(),
                    draft.manifestJson(), draft.manifestChecksum(), draft.evidenceRetainedUntil().toEpochMilli(),
                    draft.retainedUntil().toEpochMilli(), draft.reuseExpiresAt().toEpochMilli(), draft.payloadJson(), now, now);
            return find(draft.key(), false).orElseThrow();
        });
    }

    @Override
    public Optional<Published> read(Key key, String currentOwner, String capability, Mode mode) {
        Objects.requireNonNull(key); Objects.requireNonNull(mode);
        ReportLifecycleStore.require(currentOwner, "REPORT_OWNER_INVALID");
        ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
        return tx(() -> {
            Published report = find(key, false).orElse(null);
            if (report == null) return Optional.empty();
            if (!READY.equals(status(key))) fail("REPORT_NOT_READY");
            if (!report.owner().equals(currentOwner) || !report.capability().equals(capability))
                throw new SecurityException("REPORT_ACCESS_DENIED");
            long now = clock.millis();
            if (report.evidenceRetainedUntil().toEpochMilli() <= now
                    || report.retainedUntil().toEpochMilli() <= now
                    || (mode == Mode.EXPORT && report.reuseExpiresAt().toEpochMilli() <= now))
                fail(mode == Mode.EXPORT ? "REPORT_REUSE_EXPIRED" : "REPORT_DATA_EXPIRED");
            return Optional.of(report);
        });
    }

    @Override
    public long retain(Key key, String referenceId, String currentOwner, String capability) {
        ReportLifecycleStore.require(referenceId, "REPORT_REFERENCE_INVALID");
        return tx(() -> {
            read(key, currentOwner, capability, Mode.HISTORY_VIEW).orElseThrow(() -> new IllegalStateException("REPORT_NOT_FOUND"));
            int inserted = jdbc.update("INSERT INTO campaign_report_reference (report_id,revision,reference_id,created_at) VALUES (?,?,?,?)",
                    key.reportId(), key.revision(), referenceId, clock.millis());
            if (inserted == 1) jdbc.update("UPDATE campaign_report_lifecycle SET reference_count=reference_count+1,row_version=row_version+1,updated_at=? WHERE report_id=? AND revision=?",
                    clock.millis(), key.reportId(), key.revision());
            return version(key);
        });
    }

    @Override
    public long release(Key key, String referenceId) {
        ReportLifecycleStore.require(referenceId, "REPORT_REFERENCE_INVALID");
        return tx(() -> {
            int deleted = jdbc.update("DELETE FROM campaign_report_reference WHERE report_id=? AND revision=? AND reference_id=?",
                    key.reportId(), key.revision(), referenceId);
            if (deleted == 1) jdbc.update("UPDATE campaign_report_lifecycle SET reference_count=reference_count-1,row_version=row_version+1,updated_at=? WHERE report_id=? AND revision=? AND reference_count>0",
                    clock.millis(), key.reportId(), key.revision());
            return version(key);
        });
    }

    @Override
    public boolean cleanup(Key key, long expectedVersion) {
        if (expectedVersion < 1) throw new IllegalArgumentException("REPORT_VERSION_INVALID");
        return tx(() -> jdbc.update("DELETE FROM campaign_report_lifecycle WHERE report_id=? AND revision=? AND status='READY' AND row_version=? AND reference_count=0 AND retained_until<=?",
                key.reportId(), key.revision(), expectedVersion, clock.millis()) == 1);
    }

    private Optional<Published> find(Key key, boolean lock) {
        String sql = "SELECT report_id,revision,run_id,plan_revision,owner_name,capability,manifest_json,manifest_checksum,evidence_retained_until,retained_until,reuse_expires_at,payload_json,row_version,reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?"
                + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, this::map, key.reportId(), key.revision()).stream().findFirst();
    }

    private Published map(ResultSet rs, int row) throws java.sql.SQLException {
        return new Published(new Key(rs.getString("report_id"), rs.getInt("revision")), rs.getString("run_id"),
                rs.getInt("plan_revision"), rs.getString("owner_name"), rs.getString("capability"),
                rs.getString("manifest_json"), rs.getString("manifest_checksum"),
                instant(rs.getLong("evidence_retained_until")),
                instant(rs.getLong("retained_until")), instant(rs.getLong("reuse_expires_at")), rs.getString("payload_json"),
                rs.getLong("row_version"), rs.getInt("reference_count"));
    }

    private String status(Key key) { return jdbc.queryForObject("SELECT status FROM campaign_report_lifecycle WHERE report_id=? AND revision=?", String.class, key.reportId(), key.revision()); }
    private long version(Key key) { return jdbc.queryForObject("SELECT row_version FROM campaign_report_lifecycle WHERE report_id=? AND revision=?", Long.class, key.reportId(), key.revision()); }
    private static Instant instant(long value) { return Instant.ofEpochMilli(value); }
    private static boolean same(Published value, Draft draft) {
        return value.key().equals(draft.key()) && value.runId().equals(draft.runId()) && value.planRevision() == draft.planRevision()
                && value.owner().equals(draft.owner()) && value.capability().equals(draft.capability())
                && value.manifestJson().equals(draft.manifestJson())
                && value.manifestChecksum().equalsIgnoreCase(draft.manifestChecksum())
                && value.evidenceRetainedUntil().equals(draft.evidenceRetainedUntil()) && value.retainedUntil().equals(draft.retainedUntil())
                && value.reuseExpiresAt().equals(draft.reuseExpiresAt()) && value.payloadJson().equals(draft.payloadJson());
    }
    private <T> T tx(java.util.function.Supplier<T> action) { return transactions.execute(status -> action.get()); }
    private static void fail(String code) { throw new IllegalStateException(code); }
}
