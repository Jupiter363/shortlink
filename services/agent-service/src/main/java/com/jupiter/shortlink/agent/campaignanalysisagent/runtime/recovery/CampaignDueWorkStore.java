package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable short work references and bounded retry timing, independent of execution ownership. */
public final class CampaignDueWorkStore {
    public enum State { READY, DONE, BLOCKED }
    public record Entry(WorkRef reference, State state, long dueAtMillis, int attempts, long version, String reason) {}
    public record Claim(WorkRef reference, long version) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CampaignDueWorkStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || transactions.isReadOnly()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED)
            throw new IllegalArgumentException("DUE_WORK_REQUIRES_SHARED_TRANSACTION");
    }

    /** Idempotent registration; a replay cannot reopen a blocked or completed work item. */
    public void schedule(WorkRef reference) {
        Objects.requireNonNull(reference);
        tx(() -> {
            jdbc.update("INSERT INTO campaign_due_work (work_id,run_id,work_state,due_at,attempt_count,row_version,created_at,updated_at) "
                    + "VALUES (?,?,'READY',?,0,1,?,?) ON DUPLICATE KEY UPDATE work_id=work_id",
                    reference.workId(), reference.runId(), clock.millis(), clock.millis(), clock.millis());
            requireReference(reference, read(reference.workId()));
            return null;
        });
    }

    /** Explicit trusted wake after user input/revision change. It is not a retry of an unknown model. */
    public void wake(WorkRef reference) {
        tx(() -> {
            schedule(reference);
            jdbc.update("UPDATE campaign_due_work SET work_state='READY',due_at=?,attempt_count=0,row_version=row_version+1,"
                    + "reason_code=NULL,updated_at=? WHERE work_id=? AND run_id=?",
                    clock.millis(), clock.millis(), reference.workId(), reference.runId());
            return null;
        });
    }

    /** Repairs the post-registration crash window without loading prompts or serialized proposals. */
    public int discoverRegistered(int maximum) {
        return discoverRegistered(maximum, false);
    }

    /** Public runs have one canonical request work item across preparation, planning and execution. */
    public int discoverRegistered(int maximum, boolean publicRequestsPresent) {
        bounded(maximum);
        return tx(() -> {
            var planning = jdbc.query("SELECT p.run_id,p.request_id FROM campaign_planning_request p "
                    + "LEFT JOIN campaign_due_work d ON d.work_id=p.request_id WHERE d.work_id IS NULL "
                    + (publicRequestsPresent ? "AND NOT EXISTS (SELECT 1 FROM campaign_public_request u WHERE u.run_id=p.run_id) " : "")
                    + "ORDER BY p.request_id LIMIT ?", (rs, index) -> new WorkRef(rs.getString(1), rs.getString(2)), maximum);
            planning.forEach(this::schedule);
            int remaining = maximum - planning.size();
            if (remaining == 0) return planning.size();
            var typed = jdbc.query("SELECT i.run_id,i.request_id FROM campaign_run_intake i "
                    + "LEFT JOIN campaign_due_work d ON d.work_id=i.request_id WHERE d.work_id IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM campaign_planning_request p WHERE p.run_id=i.run_id) "
                    + (publicRequestsPresent ? "AND NOT EXISTS (SELECT 1 FROM campaign_public_request u WHERE u.run_id=i.run_id) " : "")
                    + "ORDER BY i.request_id LIMIT ?", (rs, index) -> new WorkRef(rs.getString(1), rs.getString(2)), remaining);
            typed.forEach(this::schedule);
            return planning.size() + typed.size();
        });
    }

    public List<Entry> due(int maximum) {
        bounded(maximum);
        return jdbc.query("SELECT work_id,run_id,work_state,due_at,attempt_count,row_version,reason_code FROM campaign_due_work "
                + "WHERE work_state='READY' AND due_at<=? ORDER BY due_at,work_id LIMIT ?", (rs, row) -> new Entry(
                    new WorkRef(rs.getString("run_id"), rs.getString("work_id")), State.valueOf(rs.getString("work_state")),
                    rs.getLong("due_at"), rs.getInt("attempt_count"), rs.getLong("row_version"), rs.getString("reason_code")),
                clock.millis(), maximum);
    }

    /** CAS consumes a scheduling opportunity, not an execution lease or proof of dead callbacks. */
    public Optional<Claim> claim(Entry expected, long initialDelayMillis, long maximumDelayMillis) {
        Objects.requireNonNull(expected);
        if (initialDelayMillis < 1 || maximumDelayMillis < initialDelayMillis)
            throw new IllegalArgumentException("DUE_WORK_BACKOFF_INVALID");
        long delay = initialDelayMillis;
        for (int i = 0; i < Math.min(expected.attempts(), 63) && delay < maximumDelayMillis; i++)
            delay = delay > maximumDelayMillis / 2 ? maximumDelayMillis : Math.min(maximumDelayMillis, delay * 2);
        long next = Math.addExact(clock.millis(), delay);
        int changed = jdbc.update("UPDATE campaign_due_work SET due_at=?,attempt_count=?,row_version=row_version+1,updated_at=? "
                + "WHERE work_id=? AND run_id=? AND row_version=? AND work_state='READY' AND due_at<=?",
                next, Math.min(Integer.MAX_VALUE - 1, expected.attempts()) + 1, clock.millis(),
                expected.reference().workId(), expected.reference().runId(), expected.version(), clock.millis());
        return changed == 1 ? Optional.of(new Claim(expected.reference(), expected.version() + 1)) : Optional.empty();
    }

    /** A stale completion cannot overwrite a newer wake or scheduling claim. */
    public void finish(Claim claim, State state, String reason) {
        Objects.requireNonNull(claim); Objects.requireNonNull(state);
        if (reason != null && !reason.matches("[A-Z][A-Z0-9_]{0,95}")) throw new IllegalArgumentException("DUE_WORK_REASON_INVALID");
        jdbc.update("UPDATE campaign_due_work SET work_state=?,reason_code=?,updated_at=? "
                        + "WHERE work_id=? AND run_id=? AND row_version=? AND work_state='READY'",
                state.name(), reason, clock.millis(), claim.reference().workId(), claim.reference().runId(), claim.version());
    }

    public Entry entry(WorkRef reference) { var value = read(reference.workId()); requireReference(reference, value); return value; }

    private Entry read(String workId) {
        var entries = jdbc.query("SELECT run_id,work_state,due_at,attempt_count,row_version,reason_code FROM campaign_due_work WHERE work_id=?",
                (rs, row) -> new Entry(new WorkRef(rs.getString(1), workId), State.valueOf(rs.getString(2)),
                        rs.getLong(3), rs.getInt(4), rs.getLong(5), rs.getString(6)), workId);
        if (entries.size() != 1) throw new IllegalStateException("DUE_WORK_NOT_FOUND");
        return entries.get(0);
    }
    private static void requireReference(WorkRef reference, Entry entry) {
        if (!reference.equals(entry.reference())) throw new IllegalArgumentException("DUE_WORK_REFERENCE_CHANGED");
    }
    private static void bounded(int maximum) {
        if (maximum < 1 || maximum > 4096) throw new IllegalArgumentException("DUE_WORK_SCAN_LIMIT_INVALID");
    }
    private <T> T tx(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
}
