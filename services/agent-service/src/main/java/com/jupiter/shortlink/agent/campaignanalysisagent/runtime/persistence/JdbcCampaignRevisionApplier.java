package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.ReplanCoordinator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC implementation of the replan persistence boundary.
 *
 * <p>The coordinator has already performed the side-effect-free assessment and graph
 * precompile.  This adapter is the small trusted write boundary that turns that result into a
 * new frozen run revision.  It snapshots active consumers while the base run is locked, records
 * the accepted receipt while that base is still ACTIVE, supersedes the base, and adopts every
 * compatible consumer before the one REQUIRED transaction commits.</p>
 *
 * <p>No model output, current group lookup, or latest-plan lookup is performed here.  Inputs are
 * copied only from the persisted base {@link FrozenCampaignRun}; candidate identity and each
 * consumer's executor/output/scope contract are checked before a receipt is written.</p>
 */
public final class JdbcCampaignRevisionApplier implements ReplanCoordinator.RevisionApplier {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcCampaignRunStore runs;
    private final JdbcCampaignStatisticsConsumerStore consumers;
    private final JdbcReplanReceiptStore receipts;
    private final CampaignStatisticsConsumerStore.Authorizer authorizer;
    private final java.util.function.Consumer<RunToken> additionalCallbackGate;

    public JdbcCampaignRevisionApplier(JdbcTemplate jdbc, TransactionTemplate transactions,
                                       JdbcCampaignRunStore runs,
                                       JdbcCampaignStatisticsConsumerStore consumers,
                                       JdbcReplanReceiptStore receipts,
                                       CampaignStatisticsConsumerStore.Authorizer authorizer) {
        this(jdbc, transactions, runs, consumers, receipts, authorizer, ignored -> {});
    }

    public JdbcCampaignRevisionApplier(JdbcTemplate jdbc, TransactionTemplate transactions,
                                       JdbcCampaignRunStore runs, JdbcCampaignStatisticsConsumerStore consumers,
                                       JdbcReplanReceiptStore receipts, CampaignStatisticsConsumerStore.Authorizer authorizer,
                                       java.util.function.Consumer<RunToken> additionalCallbackGate) {
        this.jdbc = Objects.requireNonNull(jdbc, "REPLAN_JDBC_REQUIRED");
        this.transactions = Objects.requireNonNull(transactions, "REPLAN_TRANSACTION_REQUIRED");
        this.runs = Objects.requireNonNull(runs, "REPLAN_RUN_STORE_REQUIRED");
        this.consumers = Objects.requireNonNull(consumers, "REPLAN_CONSUMER_STORE_REQUIRED");
        this.receipts = Objects.requireNonNull(receipts, "REPLAN_RECEIPT_STORE_REQUIRED");
        this.authorizer = Objects.requireNonNull(authorizer, "REPLAN_CONSUMER_AUTHORIZER_REQUIRED");
        this.additionalCallbackGate = Objects.requireNonNull(additionalCallbackGate);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || !runs.sharesTransactionDataSource(jdbc)
                || !consumers.sharesTransactionDataSource(jdbc)
                || !receipts.sharesTransactionDataSource(jdbc)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly()) {
            throw new IllegalArgumentException("Replan application requires one writable REQUIRED DataSource transaction");
        }
    }

    @Override
    public ReplanCoordinator.AppliedRevision apply(ReplanCoordinator.ApplyRequest request) {
        Objects.requireNonNull(request, "REPLAN_APPLY_REQUEST_REQUIRED");
        if (request.receipts() != receipts) throw new IllegalArgumentException("REPLAN_RECEIPT_STORE_CHANGED");

        Candidate candidate = validateCandidate(request);
        Optional<ReplanCoordinator.AppliedRevision> replay = finalizedReplay(request, candidate);
        if (replay.isPresent()) return replay.get();

        try {
            return transactions.execute(status -> {
                // The snapshot and all subsequent writes share this transaction.  activeConsumers
                // locks the base row and therefore also fences a concurrent replan/cancel.
                runs.requireReplanReady(request.baseRun());
                additionalCallbackGate.accept(request.baseRun());
                List<JdbcCampaignStatisticsConsumerStore.ActiveConsumer> active = consumers.activeConsumers(request.baseRun());
                validateConsumers(request.baseRun(), candidate.definition(), active);

                // Receipt must be recorded before revise(): JdbcReplanReceiptStore intentionally
                // accepts receipts only while the base revision is ACTIVE.
                ReplanReceiptStore.Receipt receipt = receipts.record(request.baseRun(),
                        candidate.plan().revision(), candidate.planHash(), request.requestJson(), "ACCEPTED", "ACCEPTED");
                RunToken next = runs.revise(request.baseRun(), candidate.plan().revision(), candidate.definition().definitionJson());

                Set<String> adoptedIds = new HashSet<>();
                for (JdbcCampaignStatisticsConsumerStore.ActiveConsumer activeConsumer : active) {
                    CampaignStatisticsConsumerStore.Consumer old = activeConsumer.consumer();
                    CampaignStatisticsConsumerStore.Binding binding = activeConsumer.binding();
                    String id = CampaignStatisticsConsumerStore.consumerId(next.definition(),
                            old.expectation().stepId(), binding.bindingId());
                    if (!adoptedIds.add(id)) throw new IllegalStateException("CONSUMER_IDENTITY_COLLISION");
                    consumers.adopt(next, id, binding.bindingId(), old.expectation(), authorizer);
                }
                return new ReplanCoordinator.AppliedRevision(next, receipt);
            });
        } catch (IllegalStateException staleBase) {
            // Two trusted coordinators can pass the initial receipt lookup before either one
            // locks the base.  If the winner commits first, the loser observes the fenced old
            // token (or its now-revoked status).  Re-read the owner-scoped finalized receipt
            // after the failed transaction and replay only an exact request/candidate match.
            // Without that receipt the original fencing failure remains authoritative.
            if (!staleBaseAfterConcurrentCommit(staleBase)) throw staleBase;
            Optional<ReplanCoordinator.AppliedRevision> finalized = finalizedReplay(request, candidate);
            if (finalized.isPresent()) return finalized.get();
            throw staleBase;
        }
    }

    private static boolean staleBaseAfterConcurrentCommit(IllegalStateException failure) {
        return "RUN_TOKEN_FENCED".equals(failure.getMessage())
                || "RUN_NOT_ACTIVE".equals(failure.getMessage())
                || "REPLAN_RUN_NOT_FOUND".equals(failure.getMessage())
                || "REPLAN_RUN_TOKEN_INVALID".equals(failure.getMessage());
    }

    private Candidate validateCandidate(ReplanCoordinator.ApplyRequest request) {
        RunToken base = request.baseRun();
        PlanSpec plan = request.candidate();
        PlanningAssessment assessment = request.candidateAssessment();
        ReplanCoordinator.PreparedGraph graph = request.graph();
        if (base == null || base.definition() == null || plan == null || assessment == null || graph == null
                || request.requestJson() == null || request.requestJson().isBlank()) {
            throw new IllegalArgumentException("REPLAN_TYPED_PAYLOAD_REQUIRED");
        }
        if (!PlanSpec.SCHEMA_VERSION.equals(plan.schemaVersion()))
            throw new IllegalArgumentException("REPLAN_PLAN_SCHEMA_INVALID");
        if (!plan.planId().equals(graph.planId()) || plan.revision() != graph.revision()
                || !ReplanRequest.planHash(plan).equals(graph.candidatePlanHash())) {
            throw new IllegalStateException("REPLAN_GRAPH_IDENTITY_CHANGED");
        }
        if (plan.revision() != base.definition().revision() + 1)
            throw new IllegalStateException("REPLAN_REVISION_GAP");

        FrozenCampaignRun baseFrozen = FrozenCampaignRun.read(base.definition());
        if (!baseFrozen.plan().planId().equals(plan.planId())
                || !baseFrozen.plan().runId().equals(plan.runId())
                || !baseFrozen.plan().inputSetRef().equals(plan.inputSetRef())) {
            throw new IllegalStateException("REPLAN_PLAN_BINDING_CHANGED");
        }

        // Re-assess the serialized request at the write boundary.  This prevents a caller that
        // bypasses ReplanCoordinator from changing the baseline, goals, or evidence after graph
        // precompilation.  It also proves that the candidate assessment still covers the same
        // original requirements.
        ReplanRequest replan = decodeRequest(request.requestJson());
        ReplanRequest.ReplanAssessment assessed = replan.assess(plan, assessment);
        if (!assessed.accepted()) throw new IllegalStateException("REPLAN_NOT_ACCEPTED:" + assessed.reasonCode());
        if (!assessed.candidatePlanHash().equals(graph.candidatePlanHash()))
            throw new IllegalStateException("REPLAN_GRAPH_IDENTITY_CHANGED");

        FrozenCampaignRun frozen = FrozenCampaignRun.freeze(plan, baseFrozen.inputs(), assessment);
        RunDefinition definition = frozen.definition(base.definition().caller(), base.definition().sessionId());
        return new Candidate(plan, assessed.candidatePlanHash(), definition);
    }

    private void validateConsumers(RunToken base, RunDefinition candidate,
                                   List<JdbcCampaignStatisticsConsumerStore.ActiveConsumer> active) {
        Set<String> ids = new HashSet<>();
        for (JdbcCampaignStatisticsConsumerStore.ActiveConsumer value : active) {
            CampaignStatisticsConsumerStore.Consumer consumer = value.consumer();
            CampaignStatisticsConsumerStore.Binding binding = value.binding();
            if (!consumer.active() || !base.definition().runId().equals(consumer.runId())
                    || base.definition().revision() != consumer.revision()
                    || !base.definition().caller().equals(binding.owner())
                    || !base.definition().runId().equals(binding.producerRunId())
                    || binding.producerRevision() < 1
                    || binding.producerRevision() > base.definition().revision()) {
                throw new IllegalStateException("CONSUMER_BASE_BINDING_CHANGED");
            }
            // This checks step existence, executor, output contract, and frozen scope/period
            // bindings against the candidate before any durable receipt is created.
            JdbcCampaignStatisticsConsumerStore.validateExpectedStep(candidate, consumer.expectation());
            String candidateId = CampaignStatisticsConsumerStore.consumerId(candidate,
                    consumer.expectation().stepId(), binding.bindingId());
            if (!ids.add(candidateId)) throw new IllegalStateException("CONSUMER_IDENTITY_COLLISION");
        }
    }

    private Optional<ReplanCoordinator.AppliedRevision> finalizedReplay(
            ReplanCoordinator.ApplyRequest request, Candidate candidate) {
        var existing = receipts.findFinalized(request.baseRun().definition().caller(),
                request.baseRun().definition().runId(), request.baseRun().definition().revision());
        if (existing.isEmpty()) return Optional.empty();
        ReplanReceiptStore.Receipt receipt = existing.get();
        if (!"ACCEPTED".equals(receipt.decision())
                || receipt.candidateRevision() != candidate.plan().revision()
                || !receipt.candidatePlanHash().equals(candidate.planHash())
                || !receipt.requestJson().equals(request.requestJson())) {
            throw new IllegalStateException("REPLAN_RECEIPT_CONFLICT");
        }
        var latest = runs.loadRun(request.baseRun().definition().caller(), request.baseRun().definition().runId())
                .orElseThrow(() -> new IllegalStateException("REPLAN_APPLY_INCOMPLETE"));
        if (latest.definition().revision() != candidate.plan().revision()
                || !latest.definition().equals(candidate.definition())
                || latest.status() != CampaignRunStore.RunStatus.ACTIVE) {
            throw new IllegalStateException("REPLAN_APPLY_INCOMPLETE");
        }
        return Optional.of(new ReplanCoordinator.AppliedRevision(latest.token(), receipt));
    }

    private static ReplanRequest decodeRequest(String requestJson) {
        try {
            return JSON.readValue(requestJson, ReplanRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("REPLAN_REQUEST_INVALID", invalid);
        }
    }

    private record Candidate(PlanSpec plan, String planHash, RunDefinition definition) {
        private Candidate {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(planHash);
            Objects.requireNonNull(definition);
        }
    }
}
