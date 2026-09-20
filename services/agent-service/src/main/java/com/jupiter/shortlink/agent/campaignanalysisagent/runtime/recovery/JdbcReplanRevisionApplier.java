package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies a replan revision as one database transaction. The callbacks are deliberately
 * small persistence boundaries: callers provide the SQL for their schema, while this
 * class owns ordering and atomicity.
 */
public final class JdbcReplanRevisionApplier {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcReplanRevisionApplier(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly()) {
            throw new IllegalArgumentException("Replan application requires one writable REQUIRED DataSource transaction");
        }
    }

    /** Executes lock, adoption, revision insertion, then receipt recording in that order. */
    public void apply(Request request, RevisionLock lock, ConsumerAdoption adoption,
                      RevisionInsertion insertion, ReceiptRecording receipt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(adoption, "adoption");
        Objects.requireNonNull(insertion, "insertion");
        Objects.requireNonNull(receipt, "receipt");
        transactions.executeWithoutResult(status -> {
            requireTransaction("before callback");
            invoke(lock, request, "lock");
            invoke(adoption, request, "consumer adoption");
            invoke(insertion, request, "revision insertion");
            invoke(receipt, request, "receipt recording");
            requireTransaction("after callback");
        });
    }

    private static void invoke(Callback callback, Request request, String name) {
        requireTransaction("before " + name);
        callback.run(request);
        // A callback must use the transaction supplied by this applier. This catches
        // callbacks that clear/suspend the transaction or otherwise return outside it.
        requireTransaction("after " + name);
    }

    private static void requireTransaction(String point) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("REPLAN_CALLBACK_OUTSIDE_TRANSACTION: " + point);
        }
    }

    public record Request(String runId, int baseRevision, int candidateRevision) {
        public Request {
            if (runId == null || runId.isBlank() || baseRevision < 1 || candidateRevision < 1) {
                throw new IllegalArgumentException("REPLAN_REQUEST_INVALID");
            }
        }
    }

    @FunctionalInterface
    public interface Callback {
        void run(Request request);
    }

    @FunctionalInterface
    public interface RevisionLock extends Callback {}

    @FunctionalInterface
    public interface ConsumerAdoption extends Callback {}

    @FunctionalInterface
    public interface RevisionInsertion extends Callback {}

    @FunctionalInterface
    public interface ReceiptRecording extends Callback {}
}
