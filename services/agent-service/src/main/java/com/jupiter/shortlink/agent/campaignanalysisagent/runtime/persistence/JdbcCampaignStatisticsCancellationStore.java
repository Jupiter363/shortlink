package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcStatisticsConsumerGate.require;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcStatisticsConsumerGate.failure;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore.CancelIntent;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A durable, single-use cancel admission followed only by reads of the original job. No network or retry loop. */
public final class JdbcCampaignStatisticsCancellationStore implements CampaignStatisticsCancellationStore {
    private static final String COLUMNS = "binding_id,binding_version,operation_state,observed_state,reason_code,"
            + "attempt_id,attempt_version,attempt_purpose,callback_active,dispatch_run_id,dispatch_revision,"
            + "dispatch_definition_hash,dispatch_run_version,dispatch_run_token,attempt_response_hash,observed_status_hash";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final JdbcCampaignStatisticsConsumerStore consumers;
    private final JdbcStatisticsConsumerGate gate;

    public JdbcCampaignStatisticsCancellationStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                                    JdbcCampaignStatisticsConsumerStore consumers) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.consumers = Objects.requireNonNull(consumers);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || !consumers.sharesTransactionDataSource(jdbc)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED || transactions.isReadOnly())
            throw new IllegalArgumentException("Cancellation requires one writable REQUIRED DataSource transaction");
        gate = new JdbcStatisticsConsumerGate(jdbc, clock);
        require(gate.schemaAvailable(), "CANCELLATION_SCHEMA_UNAVAILABLE");
        jdbc.query("SELECT binding_id FROM campaign_statistics_cancellation WHERE 1=0", (rs, row) -> rs.getString(1));
    }

    @Override public Optional<Operation> request(RunToken current, String bindingId) {
        return transaction(() -> {
            Binding binding = currentBinding(current, bindingId);
            // Validate the actual source before changing the existing shared intent, not from a caller-supplied jobId.
            require(binding.expiresAtMillis() > clock.millis(), "CANCELLATION_JOB_EXPIRED");
            consumers.cancellationSource(binding);
            var decision = consumers.requestCancel(current, bindingId);
            Optional<Stored> existing = find(bindingId);
            if (existing.isPresent()) {
                require(existing.get().operation().bindingVersion() == decision.bindingVersion(), "CANCELLATION_BINDING_CHANGED");
                return Optional.of(existing.get().operation());
            }
            if (decision.state() != CancelIntent.REQUESTED) return Optional.empty();
            // A historical REQUESTED without our receipt may already have reached the remote service.
            // Only this transaction's successful first CAS can create the one POST qualification.
            State state = decision.dispatchRequired() ? State.PREPARED : State.UNKNOWN;
            jdbc.update("INSERT INTO campaign_statistics_cancellation (binding_id,binding_version,operation_state,attempt_version,"
                            + "callback_active,reason_code,created_at,updated_at) VALUES (?,?,?,0,FALSE,?,?,?)",
                    bindingId, decision.bindingVersion(), state.name(),
                    state == State.UNKNOWN ? "CANCELLATION_RESULT_UNKNOWN" : null, clock.millis(), clock.millis());
            return Optional.of(required(bindingId).operation());
        });
    }

    @Override public Optional<Operation> operation(RunToken current, String bindingId) {
        return transaction(() -> {
            Binding binding = currentBinding(current, bindingId);
            return find(bindingId).map(stored -> {
                require(stored.operation().bindingVersion() == binding.version(), "CANCELLATION_BINDING_CHANGED");
                return stored.operation();
            });
        });
    }

    @Override public ReadAccess readAccess(RunToken current, String bindingId) {
        return transaction(() -> {
            Binding binding = currentBinding(current, bindingId);
            require(binding.expiresAtMillis() > clock.millis(), "CANCELLATION_JOB_EXPIRED");
            return new ReadAccess(binding, consumers.cancellationSource(binding));
        });
    }

    @Override public Permit begin(RunToken current, String bindingId) {
        // An outer transaction could roll back after HTTP, restoring PREPARED and incorrectly issuing a second POST.
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "CANCELLATION_DISPATCH_REQUIRES_COMMITTED_INTENT");
        return transaction(() -> {
            Binding binding = currentBinding(current, bindingId);
            requireIo(binding);
            Stored stored = required(bindingId); Operation operation = stored.operation();
            require(operation.bindingVersion() == binding.version(), "CANCELLATION_BINDING_CHANGED");
            require(!operation.callbackActive(), "CANCELLATION_CALLBACK_STILL_ACTIVE");
            require(operation.state() == State.PREPARED || operation.state() == State.UNKNOWN, "CANCELLATION_NOT_DISPATCHABLE");
            Purpose purpose = operation.state() == State.PREPARED ? Purpose.CANCEL : Purpose.RECONCILE;
            if (purpose == Purpose.CANCEL) require(operation.attemptVersion() == 0, "CANCELLATION_POST_ALREADY_CONSUMED");
            long attemptVersion = Math.addExact(operation.attemptVersion(), 1);
            String attemptId = "cancel-attempt-" + UUID.randomUUID();
            require(jdbc.update("UPDATE campaign_statistics_cancellation SET operation_state='DISPATCHING',attempt_id=?,attempt_version=?,"
                            + "attempt_purpose=?,dispatch_run_id=?,dispatch_revision=?,dispatch_definition_hash=?,dispatch_run_version=?,"
                            + "dispatch_run_token=?,callback_active=TRUE,attempt_response_hash=NULL,callback_exited_at=NULL,updated_at=? "
                            + "WHERE binding_id=? AND binding_version=? AND attempt_version=? AND callback_active=FALSE AND operation_state=?",
                    attemptId, attemptVersion, purpose.name(), current.definition().runId(), current.definition().revision(),
                    current.definition().definitionHash(), current.version(), current.advanceToken(), clock.millis(), bindingId,
                    binding.version(), operation.attemptVersion(), operation.state().name()) == 1, "CANCELLATION_ATTEMPT_FENCED");
            return new Permit(current, bindingId, binding.version(), attemptId, attemptVersion, purpose);
        });
    }

    @Override public boolean mayDispatch(Permit permit) {
        try {
            return transaction(() -> {
                Binding binding = currentBinding(permit.token(), permit.bindingId()); requireIo(binding);
                Stored stored = exact(permit, binding);
                return stored.operation().state() == State.DISPATCHING && stored.operation().callbackActive()
                        && stored.attemptResponseHash() == null;
            });
        } catch (IllegalArgumentException | IllegalStateException | SecurityException denied) { return false; }
    }

    @Override public Operation recordStatus(Permit permit, Status status) {
        Objects.requireNonNull(status);
        return transaction(() -> {
            Binding binding = historicalBinding(permit); Stored stored = exact(permit, binding);
            require(binding.jobId().equals(status.jobId()) && binding.expiresAtMillis() == status.expiresAtMillis()
                    && List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(status.state())
                    && status.totalRows() >= 0 && status.pageCount() >= 0
                    && (status.errorCode() == null || status.errorCode().matches("[A-Z][A-Z0-9_]{0,63}")),
                    "CANCELLATION_STATUS_INVALID");
            String hash = CampaignRunStore.sha256(JdbcStatisticsConsumerGate.encode(status));
            if (stored.attemptResponseHash() != null) {
                require(stored.attemptResponseHash().equals(hash), "CANCELLATION_STATUS_CHANGED");
                return stored.operation();
            }
            require(stored.operation().callbackActive() && stored.operation().state() != State.TERMINAL,
                    "CANCELLATION_CALLBACK_NOT_ACTIVE");
            boolean terminal = List.of("CANCELLED", "SUCCEEDED", "FAILED").contains(status.state());
            String reason = "CANCELLED".equals(status.state()) ? "CANCELLATION_CONFIRMED"
                    : terminal ? "TERMINAL_ALREADY_FINISHED" : "CANCELLATION_UNCONFIRMED";
            require(jdbc.update("UPDATE campaign_statistics_cancellation SET operation_state=?,observed_state=?,observed_error_code=?,"
                            + "observed_total_rows=?,observed_page_count=?,observed_expires_at=?,observed_status_hash=?,attempt_response_hash=?,"
                            + "reason_code=?,observed_at=?,updated_at=? WHERE binding_id=? AND binding_version=? AND attempt_id=? "
                            + "AND attempt_version=? AND callback_active=TRUE AND attempt_response_hash IS NULL",
                    terminal ? State.TERMINAL.name() : State.UNKNOWN.name(), status.state(), status.errorCode(), status.totalRows(),
                    status.pageCount(), status.expiresAtMillis(), hash, hash, reason, clock.millis(), clock.millis(), permit.bindingId(),
                    permit.bindingVersion(), permit.attemptId(), permit.attemptVersion()) == 1, "CANCELLATION_ATTEMPT_FENCED");
            if ("CANCELLED".equals(status.state())) {
                // This exact dispatched response is a historical fact even if its former current writer has been fenced.
                require(binding.cancelIntent() == CancelIntent.REQUESTED || binding.cancelIntent() == CancelIntent.CONFIRMED,
                        "CANCELLATION_INTENT_MISSING");
                if (binding.cancelIntent() == CancelIntent.REQUESTED)
                    require(jdbc.update("UPDATE campaign_statistics_job_binding SET cancel_intent='CONFIRMED',updated_at=? "
                                    + "WHERE binding_id=? AND binding_version=? AND cancel_intent='REQUESTED'",
                            clock.millis(), permit.bindingId(), permit.bindingVersion()) == 1, "CANCELLATION_BINDING_CHANGED");
            }
            // SUCCEEDED/FAILED closes this operation without mislabelling the shared intent as a confirmed cancellation.
            return required(permit.bindingId()).operation();
        });
    }

    @Override public void markUnknown(Permit permit) {
        transaction(() -> {
            Binding binding = historicalBinding(permit); Stored stored = exact(permit, binding);
            if (stored.operation().state() != State.DISPATCHING) return null;
            require(stored.operation().callbackActive(), "CANCELLATION_CALLBACK_NOT_ACTIVE");
            jdbc.update("UPDATE campaign_statistics_cancellation SET operation_state='UNKNOWN',reason_code=?,updated_at=? "
                            + "WHERE binding_id=? AND attempt_id=? AND attempt_version=? AND operation_state='DISPATCHING'",
                    unknownReason(permit.purpose()), clock.millis(), permit.bindingId(), permit.attemptId(), permit.attemptVersion());
            return null;
        });
    }

    @Override public void callbackExited(Permit permit) {
        transaction(() -> {
            Binding binding = historicalBinding(permit); Stored stored = exact(permit, binding);
            if (!stored.operation().callbackActive()) return null;
            if (stored.operation().state() == State.DISPATCHING)
                jdbc.update("UPDATE campaign_statistics_cancellation SET operation_state='UNKNOWN',reason_code=? "
                                + "WHERE binding_id=? AND attempt_id=? AND attempt_version=? AND operation_state='DISPATCHING'",
                        unknownReason(permit.purpose()), permit.bindingId(), permit.attemptId(), permit.attemptVersion());
            require(jdbc.update("UPDATE campaign_statistics_cancellation SET callback_active=FALSE,callback_exited_at=?,updated_at=? "
                            + "WHERE binding_id=? AND binding_version=? AND attempt_id=? AND attempt_version=? AND callback_active=TRUE",
                    clock.millis(), clock.millis(), permit.bindingId(), permit.bindingVersion(), permit.attemptId(), permit.attemptVersion()) == 1,
                    "CANCELLATION_ATTEMPT_FENCED");
            return null;
        });
    }

    private Binding currentBinding(RunToken token, String bindingId) {
        gate.lockRuns(token, true); Binding binding = gate.binding(bindingId); owned(token, binding); return binding;
    }
    private Binding historicalBinding(Permit permit) {
        gate.lockRuns(permit.token(), false); Binding binding = gate.binding(permit.bindingId());
        owned(permit.token(), binding);
        require(binding.version() == permit.bindingVersion() && binding.cancelIntent() != CancelIntent.NONE,
                "CANCELLATION_BINDING_CHANGED");
        return binding;
    }
    private void requireIo(Binding binding) {
        require(binding.cancelIntent() == CancelIntent.REQUESTED && !binding.localOnly()
                && binding.expiresAtMillis() > clock.millis(), "CANCELLATION_JOB_NOT_DISPATCHABLE");
        require(gate.activeConsumers(binding).isEmpty(), "CANCELLATION_CONSUMERS_ACTIVE");
        ChildRecord source = consumers.cancellationSource(binding);
        require(!source.callbackActive(), "CANCELLATION_SOURCE_CALLBACK_ACTIVE");
    }
    private Stored exact(Permit permit, Binding binding) {
        Stored stored = required(permit.bindingId()); Operation operation = stored.operation();
        require(permit.attemptId() != null && permit.attemptVersion() > 0 && permit.purpose() != null
                && binding.version() == permit.bindingVersion() && operation.bindingVersion() == permit.bindingVersion()
                && Objects.equals(operation.attemptId(), permit.attemptId()) && operation.attemptVersion() == permit.attemptVersion()
                && operation.purpose() == permit.purpose() && Objects.equals(stored.runId(), permit.token().definition().runId())
                && stored.revision() == permit.token().definition().revision()
                && Objects.equals(stored.definitionHash(), permit.token().definition().definitionHash())
                && stored.runVersion() == permit.token().version() && Objects.equals(stored.runToken(), permit.token().advanceToken()),
                "CANCELLATION_ATTEMPT_FENCED");
        return stored;
    }
    private Optional<Stored> find(String bindingId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_statistics_cancellation WHERE binding_id=? FOR UPDATE",
                (rs, row) -> read(rs), bindingId).stream().findFirst();
    }
    private Stored required(String bindingId) { return find(bindingId).orElseThrow(() -> failure("CANCELLATION_OPERATION_MISSING")); }
    private static Stored read(ResultSet rs) throws SQLException {
        String purpose = rs.getString("attempt_purpose");
        Operation operation = new Operation(rs.getString("binding_id"), rs.getLong("binding_version"),
                State.valueOf(rs.getString("operation_state")), rs.getString("observed_state"), rs.getString("reason_code"),
                rs.getString("attempt_id"), rs.getLong("attempt_version"), purpose == null ? null : Purpose.valueOf(purpose), rs.getBoolean("callback_active"));
        return new Stored(operation, rs.getString("dispatch_run_id"), rs.getInt("dispatch_revision"), rs.getString("dispatch_definition_hash"),
                rs.getLong("dispatch_run_version"), rs.getString("dispatch_run_token"), rs.getString("attempt_response_hash"));
    }
    private record Stored(Operation operation, String runId, int revision, String definitionHash, long runVersion,
                          String runToken, String attemptResponseHash) {}
    private static String unknownReason(Purpose purpose) {
        return purpose == Purpose.CANCEL ? "CANCELLATION_RESULT_UNKNOWN" : "CANCELLATION_RECONCILIATION_UNKNOWN";
    }
    private static void owned(RunToken token, Binding binding) {
        require(token.definition().runId().equals(binding.producerRunId()) && token.definition().caller().equals(binding.owner()),
                "CANCELLATION_OWNER_MISMATCH");
    }
    private <T> T transaction(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
}
