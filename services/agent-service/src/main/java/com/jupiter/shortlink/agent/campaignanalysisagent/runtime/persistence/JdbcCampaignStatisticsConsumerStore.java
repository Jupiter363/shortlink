package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol.Status;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver.Target;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcStatisticsConsumerGate.*;

/** Current consumers of an existing, remotely acknowledged statistics job. No submission or worker loop. */
public final class JdbcCampaignStatisticsConsumerStore implements CampaignStatisticsConsumerStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final JdbcCampaignRunStore runs;
    private final JdbcStatisticsConsumerGate gate;

    public JdbcCampaignStatisticsConsumerStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                                JdbcCampaignRunStore runs) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.runs = Objects.requireNonNull(runs);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || !runs.sharesTransactionDataSource(jdbc)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED || transactions.isReadOnly())
            throw new IllegalArgumentException("Consumer store requires one writable REQUIRED DataSource transaction");
        gate = new JdbcStatisticsConsumerGate(jdbc, clock);
        require(gate.schemaAvailable(), "CONSUMER_SCHEMA_UNAVAILABLE");
    }

    @Override public Binding pin(DispatchPermit permit, Status status, Target target) {
        Objects.requireNonNull(permit); Objects.requireNonNull(status); Objects.requireNonNull(target);
        return transaction(() -> {
            require(permit.purpose() == DispatchPurpose.RECONCILE, "CONSUMER_RECONCILIATION_REQUIRED");
            if (permit.adoptionLease() == null) gate.lockRuns(permit.token(), true);
            else gate.requirePermit(permit);
            require(runs.mayDispatch(permit), "CONSUMER_ATTEMPT_FENCED");
            var child = runs.child(permit.token(), permit.childId()).orElseThrow(() -> failure("CONSUMER_SOURCE_MISSING"));
            var protocol = new StatisticsJobResultProtocol(child);
            require(child.callbackActive() && child.purpose() == DispatchPurpose.RECONCILE
                    && child.attemptId().equals(permit.attemptId()) && child.attemptVersion() == permit.attemptVersion()
                    && status.jobId().equals(child.jobId()) && List.of("QUEUED", "RUNNING", "SUCCEEDED").contains(status.state())
                    && status.errorCode() == null && status.expiresAtMillis() > clock.millis(), "CONSUMER_STATUS_INVALID");
            // Constructing the protocol validates the actual stored request; a record with only jobId is insufficient.
            require(protocol.request().get("requestId").equals(child.spec().requestId()), "CONSUMER_SOURCE_CHANGED");
            if (protocol.request().get("scope") instanceof java.util.Map<?, ?> scope)
                require(target.scopeRef().equals(scope.get("parentScopeRef")), "CONSUMER_SCOPE_OR_PERIOD_CHANGED");
            Binding binding = gate.ensureBinding(permit.token(), permit.childId(), target, status.expiresAtMillis());
            require(binding.cancelIntent() == CancelIntent.NONE && !binding.localOnly(), "CONSUMER_REMOTE_RESULT_UNAVAILABLE");
            if (permit.adoptionLease() != null) {
                require(binding.bindingId().equals(permit.adoptionLease().bindingId()), "CONSUMER_BINDING_CHANGED");
                return binding;
            }
            String stepId = sourceStep(binding);
            Expectation expected = new Expectation(stepId, binding.executor(), binding.outputContractRef(), binding.requestHash(), target);
            validateExpectedStep(permit.token().definition(), expected);
            register(permit.token(), CampaignStatisticsConsumerStore.consumerId(permit.token().definition(), stepId, binding.bindingId()), binding, expected, true);
            return binding;
        });
    }

    @Override public Consumer adopt(RunToken current, String consumerId, String bindingId,
                                     Expectation expected, Authorizer authorizer) {
        Objects.requireNonNull(authorizer);
        return transaction(() -> {
            gate.lockRuns(current, true); Binding binding = gate.binding(bindingId);
            owned(current, binding);
            require(current.definition().revision() >= binding.producerRevision(), "CONSUMER_REVISION_INVALID");
            require(binding.cancelIntent() == CancelIntent.NONE && !binding.localOnly()
                    && binding.expiresAtMillis() > clock.millis(), "CONSUMER_JOB_UNAVAILABLE");
            checkExpectation(binding, expected); validateExpectedStep(current.definition(), expected);
            require(CampaignStatisticsConsumerStore.consumerId(current.definition(), expected.stepId(), bindingId).equals(consumerId),
                    "CONSUMER_IDENTITY_MISMATCH");
            RunToken source = gate.sourceToken(binding); gate.verifySourceChild(binding);
            ChildRecord child = runs.child(source, binding.producerChildId()).orElseThrow(() -> failure("CONSUMER_SOURCE_MISSING"));
            // Adoption never claims a mutable CURRENT_GROUP request is a frozen membership snapshot.
            require(child.spec().wire() != null && StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH.equals(child.spec().wire().path()),
                    "CONSUMER_FROZEN_SCOPE_REQUIRED");
            require(!child.callbackActive() && (child.state() == ChildState.WAITING || child.state() == ChildState.READY
                    || child.state() == ChildState.UNRESOLVED && child.reason() == UnresolvedReason.JOB_RESULT_UNKNOWN),
                    "CONSUMER_SOURCE_NOT_ADOPTABLE");
            requireNoCallbacks(current);
            authorize(authorizer, current, binding, expected);
            validateReadyResult(current, binding, expected, source, authorizer);
            return register(current, consumerId, binding, expected, false);
        });
    }

    @Override public Consumption resolve(RunToken current, String consumerId, Authorizer authorizer) {
        Objects.requireNonNull(authorizer);
        return transaction(() -> {
            Consumption result = gate.consumption(current, consumerId);
            validateExpectedStep(current.definition(), result.consumer().expectation());
            authorize(authorizer, current, result.binding(), result.consumer().expectation());
            validateReadyResult(current, result.binding(), result.consumer().expectation(), result.sourceToken(), authorizer);
            return result;
        });
    }

    @Override public void retire(RunToken current, String consumerId) {
        transaction(() -> {
            gate.lockRuns(current, true); Binding binding = gate.binding(gate.consumerBinding(consumerId));
            owned(current, binding); Consumer consumer = gate.consumer(consumerId);
            require(consumer.runId().equals(current.definition().runId()) && consumer.revision() == current.definition().revision(),
                    "CONSUMER_FENCED");
            if (consumer.active()) jdbc.update("UPDATE campaign_statistics_consumer SET active=FALSE,retired_at=? "
                    + "WHERE consumer_id=? AND active=TRUE", clock.millis(), consumerId);
            return null;
        });
    }

    @Override public CancelDecision requestCancel(RunToken current, String bindingId) {
        return transaction(() -> {
            gate.lockRuns(current, true); Binding binding = gate.binding(bindingId); owned(current, binding);
            if (binding.cancelIntent() != CancelIntent.NONE)
                return new CancelDecision(bindingId, binding.version(), binding.cancelIntent(), false);
            if (binding.localOnly() || !gate.activeConsumers(binding).isEmpty())
                return new CancelDecision(bindingId, binding.version(), CancelIntent.NONE, false);
            long next = Math.addExact(binding.version(), 1);
            require(jdbc.update("UPDATE campaign_statistics_job_binding SET cancel_intent='REQUESTED',binding_version=?,updated_at=? "
                    + "WHERE binding_id=? AND binding_version=? AND cancel_intent='NONE'", next, clock.millis(), bindingId, binding.version()) == 1,
                    "CONSUMER_CANCEL_FENCED");
            return new CancelDecision(bindingId, next, CancelIntent.REQUESTED, true);
        });
    }

    @Override public void confirmCancelled(RunToken current, String bindingId, long bindingVersion, Status status) {
        Objects.requireNonNull(status);
        transaction(() -> {
            gate.lockRuns(current, true); Binding binding = gate.binding(bindingId); owned(current, binding);
            require(binding.version() == bindingVersion && binding.jobId().equals(status.jobId())
                    && "CANCELLED".equals(status.state()) && status.expiresAtMillis() == binding.expiresAtMillis()
                    && binding.cancelIntent() != CancelIntent.NONE, "CONSUMER_CANCEL_CONFIRMATION_INVALID");
            if (binding.cancelIntent() == CancelIntent.REQUESTED)
                require(jdbc.update("UPDATE campaign_statistics_job_binding SET cancel_intent='CONFIRMED',updated_at=? "
                        + "WHERE binding_id=? AND binding_version=? AND cancel_intent='REQUESTED'", clock.millis(), bindingId, bindingVersion) == 1,
                        "CONSUMER_CANCEL_FENCED");
            return null;
        });
    }

    private Consumer register(RunToken token, String id, Binding binding, Expectation expected, boolean original) {
        String body = encode(expected);
        require(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65536, "CONSUMER_EXPECTATION_TOO_LARGE");
        jdbc.update("INSERT INTO campaign_statistics_consumer (consumer_id,binding_id,run_id,revision,step_id,expectation_json,expectation_hash,"
                        + "active,created_at,retired_at) VALUES (?,?,?,?,?,?,?,TRUE,?,NULL) ON DUPLICATE KEY UPDATE consumer_id=consumer_id",
                id, binding.bindingId(), token.definition().runId(), token.definition().revision(), expected.stepId(), body,
                CampaignRunStore.sha256(body), clock.millis());
        Consumer actual = gate.consumer(id);
        require(actual.bindingId().equals(binding.bindingId()) && actual.runId().equals(token.definition().runId())
                && actual.revision() == token.definition().revision() && actual.expectation().equals(expected), "CONSUMER_DEFINITION_CHANGED");
        // Repeated status reads cannot resurrect a retired original consumer.
        require(original || actual.active(), "CONSUMER_RETIRED");
        return actual;
    }

    static void validateExpectedStep(RunDefinition definition, Expectation expected) {
        require(expected != null && expected.stepId() != null && expected.executor() != null && expected.target() != null,
                "CONSUMER_EXPECTATION_INVALID");
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId()) && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()),
                "CONSUMER_INPUT_SET_CHANGED");
        PlanSpec.Step step = frozen.plan().steps().stream().filter(value -> expected.stepId().equals(value.stepId())).findFirst()
                .orElseThrow(() -> failure("CONSUMER_STEP_MISSING"));
        require(expected.outputContractRef().equals(step.outputContractRef()), "CONSUMER_OUTPUT_CONTRACT_CHANGED");
        if (step.executionMode() == PlanSpec.ExecutionMode.REACT) {
            require(step.executor() == null && step.explorationPolicy() != null
                    && step.explorationPolicy().allowedExecutors().contains(expected.executor())
                    && expected.target().scopeRef().equals(step.explorationPolicy().scopeRef())
                    && expected.target().periodsRef().equals(step.explorationPolicy().periodsRef()), "CONSUMER_POLICY_CHANGED");
        } else {
            require(expected.executor().equals(step.executor()) && step.explorationPolicy() == null, "CONSUMER_EXECUTOR_CHANGED");
            require(boundaryInput(frozen, step, "ScopeRef", expected.target().scopeRef())
                    && boundaryInput(frozen, step, "PeriodsRef", expected.target().periodsRef()), "CONSUMER_SCOPE_OR_PERIOD_CHANGED");
        }
    }

    private static boolean boundaryInput(FrozenCampaignRun frozen, PlanSpec.Step step, String type, String expected) {
        return step.inputBindings().values().stream().filter(binding -> binding.source() == PlanBinding.Source.INPUT).anyMatch(binding -> {
            var port = frozen.inputs().inputContracts().get(binding.input());
            var required = type.equals("ScopeRef") ? FrozenStatisticsJobQuery.SCOPE_TYPE : FrozenStatisticsJobQuery.PERIODS_TYPE;
            return port != null && required.equals(port.type()) && expected.equals(frozen.inputs().inputValues().get(binding.input()));
        });
    }

    private String sourceStep(Binding binding) {
        var ids = jdbc.query("SELECT step_id FROM campaign_action_ledger WHERE run_id=? AND revision=? AND action_id=?",
                (rs, row) -> rs.getString(1), binding.producerRunId(), binding.producerRevision(), binding.actionId());
        require(ids.size() == 1, "CONSUMER_SOURCE_ACTION_MISSING"); return ids.get(0);
    }
    private void validateReadyResult(RunToken current, Binding binding, Expectation expected,
                                     RunToken source, Authorizer authorizer) {
        ChildRecord child = runs.child(source, binding.producerChildId()).orElseThrow(() -> failure("CONSUMER_SOURCE_MISSING"));
        if (child.state() != ChildState.READY) return;
        require(binding.target().artifactId().equals(child.artifactId()), "CONSUMER_RESULT_TARGET_CHANGED");
        Artifact artifact = runs.readArtifact(current.definition().caller(), child.artifactId(),
                (owner, metadata) -> authorizer.mayConsume(current, binding, expected));
        ArtifactMetadata metadata = artifact.metadata(); ArtifactRef ref = metadata.ref();
        require(binding.owner().equals(metadata.owner()) && binding.producerRunId().equals(metadata.runId())
                        && binding.producerRevision() == metadata.revision() && binding.actionId().equals(metadata.actionId())
                        && binding.producerChildId().equals(metadata.childId()) && binding.executor().version().equals(metadata.executorVersion())
                        && binding.target().scopeRef().equals(ref.scopeRef()) && binding.target().periodsRef().equals(ref.periodsRef())
                        && CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(ref.type())
                        && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(ref.schemaVersion())
                        && ref.expiresAt() != null && ref.expiresAt().toEpochMilli() == binding.expiresAtMillis(),
                "CONSUMER_RESULT_PRODUCER_CHANGED");
    }
    private void requireNoCallbacks(RunToken current) {
        require(jdbc.query("SELECT child_id FROM campaign_child_ledger WHERE run_id=? AND callback_active=TRUE LIMIT 1 FOR UPDATE",
                (rs, row) -> rs.getString(1), current.definition().runId()).isEmpty(), "CONSUMER_CALLBACK_STILL_ACTIVE");
        new JdbcExplorationCallbackGate(jdbc).requireNoActive(current.definition().runId());
    }
    private static void owned(RunToken token, Binding binding) {
        require(token.definition().runId().equals(binding.producerRunId()) && token.definition().caller().equals(binding.owner()),
                "CONSUMER_OWNER_MISMATCH");
    }
    private static void authorize(Authorizer authorizer, RunToken current, Binding binding, Expectation expected) {
        if (!authorizer.mayConsume(current, binding, expected)) throw new SecurityException("CONSUMER_NOT_AUTHORIZED");
    }
    private <T> T transaction(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
}
