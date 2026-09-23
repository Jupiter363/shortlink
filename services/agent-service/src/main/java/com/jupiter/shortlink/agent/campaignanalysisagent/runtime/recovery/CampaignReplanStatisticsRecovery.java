package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.*;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import java.time.Clock;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact current-query grant for an existing frozen producer; never grants access by job id alone. */
public final class CampaignReplanStatisticsRecovery {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private CampaignReplanStatisticsRecovery() {}

    public static CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer(JdbcTemplate jdbc,
            Function<RunDefinition, StatisticsJobFixedExecutor.QueryAuthorizer> queryGates) {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(queryGates);
        return (current, binding, expected) -> {
            try {
                if (!current.definition().caller().equals(binding.owner())
                        || !current.definition().runId().equals(binding.producerRunId())
                        || binding.producerRevision() > current.definition().revision()
                        || !StatisticsJobFixedExecutor.REF.equals(binding.executor())
                        || !binding.executor().equals(expected.executor())
                        || !binding.outputContractRef().equals(expected.outputContractRef())
                        || !binding.requestHash().equals(expected.requestHash()) || !binding.target().equals(expected.target())) return false;
                var wires = jdbc.query("SELECT c.wire_method,c.wire_path,c.wire_body,c.wire_hash,c.request_id,c.job_id,c.action_id,"
                                + "a.executor_kind,a.executor_name,a.executor_version,r.definition_hash "
                                + "FROM campaign_child_ledger c JOIN campaign_action_ledger a ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                                + "JOIN campaign_run_ledger r ON r.run_id=c.run_id AND r.revision=c.revision "
                                + "WHERE c.run_id=? AND c.revision=? AND c.child_id=? AND c.child_mode='ASYNC'",
                        (rs, row) -> {
                            WireRequest wire = new WireRequest(rs.getString(1), rs.getString(2), rs.getString(3));
                            if (!"POST".equals(wire.method()) || !FrozenStatisticsJobQuery.FROZEN_SUBMIT_PATH.equals(wire.path())
                                    || !wire.hash().equals(binding.requestHash()) || !wire.hash().equals(rs.getString(4))
                                    || !binding.requestId().equals(rs.getString(5)) || !binding.jobId().equals(rs.getString(6))
                                    || !binding.actionId().equals(rs.getString(7))
                                    || !binding.executor().kind().name().equals(rs.getString(8))
                                    || !binding.executor().name().equals(rs.getString(9)) || !binding.executor().version().equals(rs.getString(10))
                                    || !binding.producerDefinitionHash().equals(rs.getString(11)))
                                throw new SecurityException("REPLAN_SOURCE_QUERY_CHANGED");
                            return wire;
                        }, binding.producerRunId(), binding.producerRevision(), binding.producerChildId());
                if (wires.size() != 1) return false;
                var producers = jdbc.query("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_json FROM campaign_run_ledger WHERE run_id=? AND revision=?",
                        (rs,row) -> new RunDefinition(new Caller(rs.getString(1),rs.getString(2),rs.getLong(3)),rs.getString(4),
                                binding.producerRunId(),rs.getString(5),binding.producerRevision(),rs.getString(6)),
                        binding.producerRunId(),binding.producerRevision());
                if (producers.size()!=1 || !producers.get(0).definitionHash().equals(binding.producerDefinitionHash())) return false;
                var producer = FrozenStatisticsJobQuery.resolve(producers.get(0), expected.executor()).get(expected.stepId());
                if (producer == null || !producer.child().childId().equals(binding.producerChildId())
                        || !producer.child().actionId().equals(binding.actionId()) || !producer.child().wire().equals(wires.get(0))
                        || !producer.target().equals(binding.target())) return false;
                Map<String,Object> original = JSON.readValue(wires.get(0).bodyJson(), new TypeReference<>() {});
                if (!binding.requestId().equals(original.get("requestId"))) return false;
                Map<String,Object> semantic = new TreeMap<>(original); semantic.remove("requestId");
                FrozenCampaignRun frozen = FrozenCampaignRun.read(current.definition());
                PlanSpec.Step step = frozen.plan().steps().stream().filter(s -> s.stepId().equals(expected.stepId())).findFirst().orElseThrow();
                if (!step.outputContractRef().equals(expected.outputContractRef())) return false;
                Map<String,Object> now;
                if (step.executionMode() == PlanSpec.ExecutionMode.FIXED) {
                    var bound = FrozenStatisticsJobQuery.resolve(current.definition(), expected.executor()).get(step.stepId());
                    if (bound == null || !bound.scopeRef().equals(binding.target().scopeRef())
                            || !bound.periodsRef().equals(binding.target().periodsRef())) return false;
                    now = new TreeMap<>(bound.request()); now.remove("requestId");
                } else return false; // REACT needs its original CALL continuation, not a replacement session.
                var owner = current.definition().caller();
                return JSON.writeValueAsString(semantic).equals(JSON.writeValueAsString(now)) && queryGates.apply(current.definition()).mayUse(
                        new AgentPrincipal(owner.tenantId(), owner.subject(), owner.authVersion(), false),
                        binding.target().scopeRef(), binding.target().periodsRef(), original);
            } catch (Exception denied) { return false; }
        };
    }

    public static Runtime open(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, RunToken token,
            AgentPrincipal principal, CampaignRunStore runs, CampaignStepStore steps,
            CampaignStatisticsConsumerStore consumers, StatisticsJobResultReceiver receiver,
            CampaignStatisticsConsumerStore.Authorizer grant, ShortLinkBusinessGateway gateway,
            StatisticsJobFixedExecutor.QueryAuthorizer queryGate) {
        return new Runtime(jdbc, transactions, clock, token, principal, runs, steps, consumers, receiver, grant, gateway, queryGate);
    }

    /** A current fixed step consumes its actual original child; no copied child or fallback submission. */
    public static final class Runtime {
        private static final String WAITING = "ADOPTED_STATISTICS_WAITING";
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transactions;
        private final Clock clock;
        private final RunToken token;
        private final AgentPrincipal principal;
        private final CampaignRunStore runs;
        private final CampaignStepStore steps;
        private final CampaignStatisticsConsumerStore consumers;
        private final StatisticsJobResultReceiver receiver;
        private final CampaignStatisticsConsumerStore.Authorizer grant;
        private final ShortLinkBusinessGateway gateway;
        private final StatisticsJobFixedExecutor.QueryAuthorizer queryGate;
        private Runtime(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, RunToken token,
                AgentPrincipal principal, CampaignRunStore runs, CampaignStepStore steps,
                CampaignStatisticsConsumerStore consumers, StatisticsJobResultReceiver receiver,
                CampaignStatisticsConsumerStore.Authorizer grant, ShortLinkBusinessGateway gateway,
                StatisticsJobFixedExecutor.QueryAuthorizer queryGate) {
            this.jdbc=Objects.requireNonNull(jdbc); this.transactions=Objects.requireNonNull(transactions);
            this.clock=Objects.requireNonNull(clock); this.token=Objects.requireNonNull(token);
            this.principal=Objects.requireNonNull(principal); this.runs=Objects.requireNonNull(runs);
            this.steps=Objects.requireNonNull(steps); this.consumers=Objects.requireNonNull(consumers);
            this.receiver=Objects.requireNonNull(receiver); this.grant=Objects.requireNonNull(grant);
            this.gateway=Objects.requireNonNull(gateway); this.queryGate=Objects.requireNonNull(queryGate);
            if (!(transactions.getTransactionManager() instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager manager)
                    || manager.getDataSource()!=jdbc.getDataSource() || transactions.isReadOnly()
                    || transactions.getPropagationBehavior()!=org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED)
                throw new IllegalArgumentException("ADOPTED_STATISTICS_TRANSACTION_REQUIRED");
        }

        public void prepareRecovery() {
            for (String id : ids(null)) {
                var use = consumers.resolve(token, id, grant);
                receiver.receiveAdopted(token, id, principal, () -> authorized(id));
                transactions.execute(status -> {
                    if (!steps.mayAdvance(token)) return null;
                    var actual = consumers.resolve(token, id, grant);
                    var child = runs.child(actual.sourceToken(), actual.binding().producerChildId()).orElseThrow();
                    if (child.state() != ChildState.READY) return null;
                    jdbc.update("UPDATE campaign_step_ledger SET step_status='READY',reason=NULL,row_version=row_version+1,updated_at=? "
                                    + "WHERE run_id=? AND revision=? AND step_id=? AND step_status='BLOCKED' AND reason=? AND callback_active=FALSE",
                            clock.millis(), token.definition().runId(), token.definition().revision(), use.consumer().expectation().stepId(), WAITING);
                    return null;
                });
            }
        }

        public PersistentPlanDriver.FixedExecutor registration(StatisticsJobFixedExecutor normal) {
            var original = normal.registration();
            return new PersistentPlanDriver.FixedExecutor(original.ref(), new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { original.policy().validateInputs(step, inputs); }
                public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String,BoundArtifact> outputs) {
                    var use = adopted(step.stepId());
                    if (use == null) { original.policy().validateOutputs(step, inputs, outputs); return; }
                    original.policy().validateInputs(step, inputs);
                    var source = new StatisticsJobFixedExecutor(use.sourceToken().definition(), principal, gateway, queryGate);
                    var sourceStep = FrozenCampaignRun.read(use.sourceToken().definition()).plan().steps().stream()
                            .filter(value -> value.stepId().equals(step.stepId())).findFirst().orElseThrow();
                    source.registration().policy().validateOutputs(sourceStep, inputs, outputs);
                }
            }, context -> {
                var use = adopted(context.step().stepId());
                if (use == null) {
                    var result = original.executor().execute(context);
                    var query = FrozenStatisticsJobQuery.resolve(token.definition(),StatisticsJobFixedExecutor.REF).get(context.step().stepId());
                    if (result.status() == CampaignStepStore.StepStatus.WAITING && query.request().containsKey("scope")) {
                        receiver.receive(token,query.child().childId(),principal,query.target(),
                                () -> queryGate.mayUse(principal,query.scopeRef(),query.periodsRef(),query.request()));
                        var actual = runs.child(token,query.child().childId()).orElseThrow();
                        if (actual.state()==ChildState.READY) return PersistentPlanDriver.Result.succeeded(Map.of(StatisticsJobFixedExecutor.OUTPUT_NAME,query.target().artifactId()));
                        if (actual.state()!=ChildState.WAITING) return PersistentPlanDriver.Result.blocked("STEP_RESULT_UNKNOWN");
                    }
                    return result;
                }
                original.policy().validateInputs(context.step(), context.inputs());
                context.requireCurrent();
                var child = runs.child(use.sourceToken(), use.binding().producerChildId()).orElseThrow();
                return child.state() == ChildState.READY
                        ? PersistentPlanDriver.Result.succeeded(Map.of(StatisticsJobFixedExecutor.OUTPUT_NAME, use.binding().target().artifactId()))
                        : PersistentPlanDriver.Result.blocked(WAITING);
            });
        }

        private CampaignStatisticsConsumerStore.Consumption adopted(String step) {
            var values = ids(step);
            if (values.isEmpty()) return null;
            if (values.size() != 1) throw new SecurityException("ADOPTED_STATISTICS_STEP_AMBIGUOUS");
            return consumers.resolve(token, values.get(0), grant);
        }
        private boolean authorized(String id) {
            try { consumers.resolve(token, id, grant); return true; }
            catch (RuntimeException denied) { return false; }
        }
        private List<String> ids(String step) {
            String sql = "SELECT c.consumer_id FROM campaign_statistics_consumer c JOIN campaign_statistics_job_binding b ON b.binding_id=c.binding_id "
                    + "WHERE c.run_id=? AND c.revision=? AND c.active=TRUE AND b.producer_revision<c.revision"
                    + (step == null ? " ORDER BY c.consumer_id" : " AND c.step_id=?");
            return step == null ? jdbc.query(sql,(rs,row)->rs.getString(1), token.definition().runId(), token.definition().revision())
                    : jdbc.query(sql,(rs,row)->rs.getString(1), token.definition().runId(), token.definition().revision(), step);
        }
    }

    /** Only a live, exactly authorized consumer can expose a producer from an earlier revision. */
    public static ArtifactAuthorizer artifactAuthorizer(JdbcTemplate jdbc, CampaignRunStore runs,
            CampaignStatisticsConsumerStore consumers, CampaignStatisticsConsumerStore.Authorizer grant) {
        return (caller, metadata) -> {
            try {
                var current = runs.loadRun(caller, metadata.runId()).orElseThrow();
                if (current.status() != RunStatus.ACTIVE || metadata.revision() >= current.definition().revision()) return false;
                var ids = jdbc.query("SELECT c.consumer_id FROM campaign_statistics_consumer c JOIN campaign_statistics_job_binding b ON b.binding_id=c.binding_id "
                                + "WHERE c.run_id=? AND c.revision=? AND c.active=TRUE AND b.artifact_id=?",
                        (rs,row) -> rs.getString(1), metadata.runId(), current.definition().revision(), metadata.ref().artifactId());
                for (String id : ids) {
                    var use = consumers.resolve(current.token(), id, grant);
                    var binding = use.binding();
                    var child = runs.child(use.sourceToken(), binding.producerChildId()).orElseThrow();
                    if (child.state() == ChildState.READY && metadata.ref().artifactId().equals(child.artifactId())
                            && binding.owner().equals(metadata.owner()) && caller.equals(metadata.owner())
                            && binding.producerRunId().equals(metadata.runId()) && binding.producerRevision() == metadata.revision()
                            && binding.actionId().equals(metadata.actionId()) && binding.producerChildId().equals(metadata.childId())
                            && use.sourceToken().definition().planId().equals(metadata.planId())
                            && binding.executor().version().equals(metadata.executorVersion())
                            && binding.target().artifactId().equals(metadata.ref().artifactId())
                            && binding.target().scopeRef().equals(metadata.ref().scopeRef())
                            && binding.target().periodsRef().equals(metadata.ref().periodsRef())
                            && CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(metadata.ref().type())
                            && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(metadata.ref().schemaVersion())
                            && metadata.ref().expiresAt() != null && metadata.ref().expiresAt().toEpochMilli() == binding.expiresAtMillis())
                        return runs.inspectArtifact(caller, metadata.ref().artifactId(), (owner, stored) -> stored.equals(metadata)).equals(metadata);
                }
                return false;
            } catch (RuntimeException denied) { return false; }
        };
    }
}
