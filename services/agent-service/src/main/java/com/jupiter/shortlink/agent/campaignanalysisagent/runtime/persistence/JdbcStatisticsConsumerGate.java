package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver.Target;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared physical-job interlock. All mutating users participate in their existing REQUIRED transaction. */
public final class JdbcStatisticsConsumerGate {
    public record ReadAccess(RunToken sourceToken, String childId, AdoptionLease lease, Target target) {}
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    static final String BINDING_COLUMNS = "binding_id,tenant_id,subject_name,auth_version,producer_run_id,producer_revision,"
            + "producer_child_id,producer_definition_hash,action_id,executor_kind,executor_name,executor_version,"
            + "output_contract_ref,job_id,request_id,request_hash,artifact_id,scope_ref,periods_ref,expires_at,"
            + "binding_version,cancel_intent,local_only";
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile boolean foundSchema;

    public JdbcStatisticsConsumerGate(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.clock = Objects.requireNonNull(clock);
    }
    public boolean schemaAvailable() {
        if (foundSchema) return true;
        boolean found = table("campaign_statistics_job_binding");
        if (found) foundSchema = true;
        return found;
    }
    private boolean table(String name) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), null, new String[]{"TABLE"})) {
                while (tables.next()) if (name.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        }));
    }

    public ReadAccess prepareRead(RunToken current, String consumerId) {
        Consumption consumption = consumption(current, consumerId);
        Binding binding = consumption.binding();
        require(!binding.localOnly(), "CONSUMER_REMOTE_RESULT_RELEASED");
        require(binding.cancelIntent() == CancelIntent.NONE, "CONSUMER_JOB_CANCEL_REQUESTED");
        require(binding.expiresAtMillis() > clock.millis(), "CONSUMER_JOB_EXPIRED");
        return new ReadAccess(consumption.sourceToken(), binding.producerChildId(),
                new AdoptionLease(binding.bindingId(), consumerId, current, binding.version()), binding.target());
    }

    /** Checks current authority only. The exact attempt is checked separately after it exists. */
    public void requirePermit(DispatchPermit permit) {
        if (!schemaAvailable()) { require(permit.adoptionLease() == null, "CONSUMER_SCHEMA_UNAVAILABLE"); return; }
        if (permit.adoptionLease() != null) {
            var lease = permit.adoptionLease(); var actual = prepareRead(lease.consumerToken(), lease.consumerId());
            require(permit.purpose() == DispatchPurpose.RECONCILE && permit.parentCall() == null
                    && actual.sourceToken().equals(permit.token()) && actual.childId().equals(permit.childId())
                    && actual.lease().equals(lease), "CONSUMER_DISPATCH_FENCED");
        } else {
            Optional<Binding> known = sourceBinding(permit.token(), permit.childId());
            if (known.isEmpty()) return;
            Binding binding = known.get(); checkSource(permit.token(), binding);
            require(binding.cancelIntent() == CancelIntent.NONE, "CONSUMER_JOB_CANCEL_REQUESTED");
            if (permit.purpose() != DispatchPurpose.RELEASE)
                require(!binding.localOnly() && binding.expiresAtMillis() > clock.millis(), "CONSUMER_REMOTE_RESULT_UNAVAILABLE");
            require(permit.purpose() != DispatchPurpose.FRESH, "CONSUMER_EXISTING_JOB_CANNOT_SUBMIT");
        }
    }

    public void requirePublication(DispatchPermit permit, ArtifactDraft draft) {
        if (permit.adoptionLease() == null) return;
        requirePermit(permit);
        Binding binding = binding(permit.adoptionLease().bindingId());
        require(binding.target().artifactId().equals(draft.artifactId())
                        && binding.target().scopeRef().equals(draft.scopeRef()) && binding.target().periodsRef().equals(draft.periodsRef())
                        && CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(draft.type())
                        && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(draft.schemaVersion())
                        && draft.expiresAt() != null && draft.expiresAt().toEpochMilli() == binding.expiresAtMillis(),
                "CONSUMER_RESULT_TARGET_CHANGED");
    }

    /** Invoked after creating the actual source-child attempt, including ordinary attempts that clear old adoption. */
    public void bindAttempt(DispatchPermit permit) {
        if (!schemaAvailable()) { require(permit.adoptionLease() == null, "CONSUMER_SCHEMA_UNAVAILABLE"); return; }
        var lease = permit.adoptionLease();
        require(jdbc.update("UPDATE campaign_child_ledger SET adoption_binding_id=?,adoption_consumer_id=?,adoption_binding_version=?,"
                        + "adoption_run_id=?,adoption_revision=?,adoption_run_version=?,adoption_run_token=? "
                        + "WHERE run_id=? AND revision=? AND child_id=? AND attempt_id=? AND attempt_version=? "
                        + "AND dispatch_run_version=? AND dispatch_run_token=? AND callback_active=TRUE",
                lease == null ? null : lease.bindingId(), lease == null ? null : lease.consumerId(), lease == null ? null : lease.bindingVersion(),
                lease == null ? null : lease.consumerToken().definition().runId(), lease == null ? null : lease.consumerToken().definition().revision(),
                lease == null ? null : lease.consumerToken().version(), lease == null ? null : lease.consumerToken().advanceToken(),
                permit.token().definition().runId(), permit.token().definition().revision(), permit.childId(), permit.attemptId(),
                permit.attemptVersion(), permit.token().version(), permit.token().advanceToken()) == 1, "CONSUMER_ATTEMPT_FENCED");
    }

    /** Cleanup validates only historical attempt facts; cancelled/retired consumers may still exit. */
    public void requireExactAttempt(DispatchPermit permit) {
        if (!schemaAvailable()) { require(permit.adoptionLease() == null, "CONSUMER_SCHEMA_UNAVAILABLE"); return; }
        var lease = permit.adoptionLease();
        var matches = jdbc.query("SELECT adoption_binding_id,adoption_consumer_id,adoption_binding_version,adoption_run_id,"
                        + "adoption_revision,adoption_run_version,adoption_run_token FROM campaign_child_ledger "
                        + "WHERE run_id=? AND revision=? AND child_id=? AND attempt_id=? AND attempt_version=? FOR UPDATE",
                (rs, row) -> lease == null
                        ? rs.getString(1) == null && rs.getString(2) == null && rs.getObject(3) == null && rs.getString(4) == null
                            && rs.getObject(5) == null && rs.getObject(6) == null && rs.getString(7) == null
                        : lease.bindingId().equals(rs.getString(1)) && lease.consumerId().equals(rs.getString(2))
                            && lease.bindingVersion() == rs.getLong(3) && lease.consumerToken().definition().runId().equals(rs.getString(4))
                            && lease.consumerToken().definition().revision() == rs.getInt(5) && lease.consumerToken().version() == rs.getLong(6)
                            && lease.consumerToken().advanceToken().equals(rs.getString(7)),
                permit.token().definition().runId(), permit.token().definition().revision(), permit.childId(), permit.attemptId(), permit.attemptVersion());
        require(matches.size() == 1 && matches.get(0), "CONSUMER_ATTEMPT_FENCED");
    }

    public Binding lockForRelease(RunToken token, String childId) {
        lockRuns(token, true);
        Binding binding = sourceBinding(token, childId).orElseGet(() -> {
            var records = jdbc.query("SELECT r.spec_json,r.spec_hash,r.published,c.child_state,c.artifact_id "
                            + "FROM campaign_statistics_receipt r JOIN campaign_child_ledger c ON c.run_id=r.run_id "
                            + "AND c.revision=r.revision AND c.child_id=r.child_id WHERE r.run_id=? AND r.revision=? AND r.child_id=?",
                    (rs, row) -> {
                        String json = rs.getString(1);
                        require(rs.getBoolean(3) && "READY".equals(rs.getString(4))
                                && CampaignRunStore.sha256(json).equals(rs.getString(2)), "CONSUMER_RELEASE_PROOF_REQUIRED");
                        var spec = decode(json, CampaignStatisticsResultStore.ReceiptSpec.class);
                        require(spec.artifactId().equals(rs.getString(5)), "CONSUMER_RELEASE_PROOF_CHANGED");
                        return spec;
                    }, token.definition().runId(), token.definition().revision(), childId);
            require(records.size() == 1, "CONSUMER_RELEASE_PROOF_REQUIRED"); var receipt = records.get(0);
            return ensureBinding(token, childId, new Target(receipt.artifactId(), receipt.scopeRef(), receipt.periodsRef()), receipt.expiresAtMillis());
        });
        checkSource(token, binding);
        require(binding.cancelIntent() == CancelIntent.NONE, "CONSUMER_JOB_CANCEL_REQUESTED");
        require(binding.expiresAtMillis() > clock.millis(), "CONSUMER_JOB_EXPIRED");
        var children = jdbc.query("SELECT child_state,artifact_id,callback_active,attempt_purpose FROM campaign_child_ledger WHERE run_id=? AND revision=? AND child_id=? FOR UPDATE",
                (rs, row) -> "READY".equals(rs.getString(1)) && binding.target().artifactId().equals(rs.getString(2))
                        && (!rs.getBoolean(3) || "RELEASE".equals(rs.getString(4))),
                binding.producerRunId(), binding.producerRevision(), binding.producerChildId());
        require(children.size() == 1 && children.get(0), "CONSUMER_RELEASE_RESULT_NOT_READY");
        for (Consumer consumer : activeConsumers(binding)) checkExpectation(binding, consumer.expectation());
        return binding;
    }

    /** Late release facts use the historical producer, not a new current-write authorization. */
    public Binding lockForReleaseFact(DispatchPermit permit) {
        require(permit.purpose() == DispatchPurpose.RELEASE && permit.adoptionLease() == null, "CONSUMER_RELEASE_PERMIT_REQUIRED");
        lockRuns(permit.token(), false);
        Binding binding = sourceBinding(permit.token(), permit.childId()).orElseThrow(() -> failure("CONSUMER_BINDING_MISSING"));
        checkSource(permit.token(), binding); return binding;
    }

    /** Same transaction as the existing verified release receipt; all future remote adoption is closed. */
    public void markLocalOnly(String bindingId) {
        transactional(); Binding binding = binding(bindingId);
        require(binding.cancelIntent() == CancelIntent.NONE, "CONSUMER_JOB_CANCEL_REQUESTED");
        jdbc.update("UPDATE campaign_statistics_job_binding SET local_only=TRUE,updated_at=? WHERE binding_id=? AND cancel_intent='NONE'",
                clock.millis(), bindingId);
    }

    Consumption consumption(RunToken current, String consumerId) {
        require(schemaAvailable(), "CONSUMER_SCHEMA_UNAVAILABLE"); lockRuns(current, true);
        String id = consumerBinding(consumerId);
        Binding binding = binding(id); Consumer consumer = consumer(consumerId);
        require(consumer.active() && binding.bindingId().equals(consumer.bindingId())
                        && current.definition().runId().equals(consumer.runId()) && current.definition().revision() == consumer.revision()
                        && current.definition().runId().equals(binding.producerRunId())
                        && current.definition().caller().equals(binding.owner()), "CONSUMER_FENCED");
        require(binding.expiresAtMillis() > clock.millis() && binding.cancelIntent() == CancelIntent.NONE, "CONSUMER_JOB_UNAVAILABLE");
        checkExpectation(binding, consumer.expectation());
        JdbcCampaignStatisticsConsumerStore.validateExpectedStep(current.definition(), consumer.expectation());
        RunToken source = sourceToken(binding); verifySourceChild(binding);
        return new Consumption(binding, consumer, source);
    }

    void lockRuns(RunToken expected, boolean current) {
        transactional(); var definition = expected.definition();
        jdbc.query("SELECT revision FROM campaign_run_ledger WHERE run_id=? ORDER BY revision FOR UPDATE",
                (rs, row) -> rs.getInt(1), definition.runId());
        var rows = jdbc.query("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,run_status,row_version,advance_token "
                        + "FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> definition.caller().tenantId().equals(rs.getString(1)) && definition.caller().subject().equals(rs.getString(2))
                        && definition.caller().authVersion() == rs.getLong(3) && definition.sessionId().equals(rs.getString(4))
                        && definition.planId().equals(rs.getString(5)) && definition.definitionHash().equals(rs.getString(6))
                        && (!current || ("ACTIVE".equals(rs.getString(7)) && expected.version() == rs.getLong(8)
                            && expected.advanceToken().equals(rs.getString(9)))), definition.runId(), definition.revision());
        require(rows.size() == 1 && rows.get(0), "CONSUMER_RUN_FENCED");
    }

    Binding ensureBinding(RunToken source, String childId, Target target, long expiresAt) {
        transactional(); require(expiresAt > clock.millis(), "CONSUMER_REMOTE_EXPIRY_REQUIRED");
        var rows = jdbc.query("SELECT c.action_id,c.job_id,c.request_id,c.wire_hash,c.child_mode,a.step_id,a.executor_kind,a.executor_name,a.executor_version "
                        + "FROM campaign_child_ledger c JOIN campaign_action_ledger a ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                        + "WHERE c.run_id=? AND c.revision=? AND c.child_id=?",
                (rs, row) -> {
                    require("ASYNC".equals(rs.getString(5)) && rs.getString(2) != null, "CONSUMER_KNOWN_JOB_REQUIRED");
                    var step = FrozenCampaignRun.read(source.definition()).plan().steps().stream().filter(value -> value.stepId().equals(rsString(rs, 6)))
                            .findFirst().orElseThrow(() -> failure("CONSUMER_SOURCE_STEP_MISSING"));
                    return new Binding(CampaignStatisticsConsumerStore.bindingId(source.definition().caller(), rs.getString(2)),
                            source.definition().caller(), source.definition().runId(), source.definition().revision(), childId,
                            source.definition().definitionHash(), rs.getString(1),
                            new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.valueOf(rs.getString(7)), rs.getString(8), rs.getString(9)),
                            step.outputContractRef(), rs.getString(2), rs.getString(3), rs.getString(4), target, expiresAt, 1, CancelIntent.NONE, false);
                }, source.definition().runId(), source.definition().revision(), childId);
        require(rows.size() == 1, "CONSUMER_SOURCE_MISSING"); Binding expected = rows.get(0);
        var owner = expected.owner(); var executor = expected.executor();
        jdbc.update("INSERT INTO campaign_statistics_job_binding (" + BINDING_COLUMNS + ",created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,'NONE',FALSE,?,?) ON DUPLICATE KEY UPDATE binding_id=binding_id",
                expected.bindingId(), owner.tenantId(), owner.subject(), owner.authVersion(), expected.producerRunId(), expected.producerRevision(),
                childId, expected.producerDefinitionHash(), expected.actionId(), executor.kind().name(), executor.name(), executor.version(),
                expected.outputContractRef(), expected.jobId(), expected.requestId(), expected.requestHash(), target.artifactId(), target.scopeRef(),
                target.periodsRef(), expiresAt, clock.millis(), clock.millis());
        Binding actual = binding(expected.bindingId());
        require(sameFacts(expected, actual), "CONSUMER_BINDING_CHANGED");
        // Pre-v19 release facts already promise local-only consumption; never reopen remote reads.
        if (table("campaign_statistics_release") && !jdbc.query("SELECT binding_id FROM campaign_statistics_release "
                        + "WHERE tenant_id=? AND subject_name=? AND job_id=? FOR UPDATE", (rs, row) -> rs.getString(1),
                owner.tenantId(), owner.subject(), expected.jobId()).isEmpty()) {
            markLocalOnly(expected.bindingId()); actual = binding(expected.bindingId());
        }
        return actual;
    }

    Binding binding(String id) {
        var rows = jdbc.query("SELECT " + BINDING_COLUMNS + " FROM campaign_statistics_job_binding WHERE binding_id=? FOR UPDATE",
                (rs, row) -> readBinding(rs), id);
        require(rows.size() == 1, "CONSUMER_BINDING_MISSING"); return rows.get(0);
    }
    Optional<Binding> sourceBinding(RunToken token, String childId) {
        return jdbc.query("SELECT " + BINDING_COLUMNS + " FROM campaign_statistics_job_binding WHERE producer_run_id=? AND producer_revision=? "
                        + "AND producer_child_id=? FOR UPDATE", (rs, row) -> readBinding(rs), token.definition().runId(), token.definition().revision(), childId)
                .stream().findFirst();
    }
    String consumerBinding(String id) {
        var rows = jdbc.query("SELECT binding_id FROM campaign_statistics_consumer WHERE consumer_id=?", (rs, row) -> rs.getString(1), id);
        require(rows.size() == 1, "CONSUMER_NOT_FOUND"); return rows.get(0);
    }
    Consumer consumer(String id) {
        var rows = jdbc.query("SELECT consumer_id,binding_id,run_id,revision,step_id,expectation_json,expectation_hash,active FROM campaign_statistics_consumer "
                + "WHERE consumer_id=? FOR UPDATE", (rs, row) -> readConsumer(rs), id);
        require(rows.size() == 1, "CONSUMER_NOT_FOUND"); return rows.get(0);
    }
    List<Consumer> activeConsumers(Binding binding) {
        return jdbc.query("SELECT c.consumer_id,c.binding_id,c.run_id,c.revision,c.step_id,c.expectation_json,c.expectation_hash,c.active "
                        + "FROM campaign_statistics_consumer c JOIN campaign_run_ledger r ON r.run_id=c.run_id AND r.revision=c.revision "
                        + "WHERE c.binding_id=? AND c.active=TRUE AND r.run_status='ACTIVE' FOR UPDATE",
                (rs, row) -> readConsumer(rs), binding.bindingId());
    }
    RunToken sourceToken(Binding binding) {
        var rows = jdbc.query("SELECT session_id,plan_id,definition_json,definition_hash,row_version,advance_token,tenant_id,subject_name,auth_version "
                        + "FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> {
                    var owner = new Caller(rs.getString(7), rs.getString(8), rs.getLong(9));
                    require(owner.equals(binding.owner()) && binding.producerDefinitionHash().equals(rs.getString(4)), "CONSUMER_SOURCE_CHANGED");
                    var definition = new RunDefinition(owner, rs.getString(1), binding.producerRunId(), rs.getString(2), binding.producerRevision(), rs.getString(3));
                    require(definition.definitionHash().equals(binding.producerDefinitionHash()), "CONSUMER_SOURCE_CHANGED");
                    return new RunToken(definition, rs.getLong(5), rs.getString(6));
                }, binding.producerRunId(), binding.producerRevision());
        require(rows.size() == 1, "CONSUMER_SOURCE_MISSING"); return rows.get(0);
    }
    void verifySourceChild(Binding binding) {
        var matches = jdbc.query("SELECT c.action_id,c.job_id,c.request_id,c.wire_hash,c.child_state,c.unresolved_reason,a.executor_kind,a.executor_name,a.executor_version,"
                        + "c.child_mode,c.wire_method,c.wire_path,c.wire_body,c.attempt_purpose "
                        + "FROM campaign_child_ledger c JOIN campaign_action_ledger a ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                        + "WHERE c.run_id=? AND c.revision=? AND c.child_id=? FOR UPDATE",
                (rs, row) -> {
                    boolean state = (List.of("WAITING", "READY").contains(rs.getString(5)) && rs.getString(6) == null)
                            || ("DISPATCHING".equals(rs.getString(5)) && rs.getString(6) == null && "RECONCILE".equals(rs.getString(14)))
                            || ("UNRESOLVED".equals(rs.getString(5)) && "JOB_RESULT_UNKNOWN".equals(rs.getString(6))
                                && "RECONCILE".equals(rs.getString(14)));
                    require("ASYNC".equals(rs.getString(10)), "CONSUMER_SOURCE_CHANGED");
                    WireRequest wire = new WireRequest(rs.getString(11), rs.getString(12), rs.getString(13));
                    require(wire.hash().equals(binding.requestHash()), "CONSUMER_SOURCE_CHANGED");
                    new StatisticsJobResultProtocol(new ChildRecord(new ChildSpec(binding.producerChildId(), binding.actionId(),
                            ChildMode.ASYNC, binding.requestId(), wire), ChildState.WAITING, binding.jobId(), null, null, 0, null, false, null));
                    return state && binding.actionId().equals(rs.getString(1)) && binding.jobId().equals(rs.getString(2))
                            && binding.requestId().equals(rs.getString(3)) && binding.requestHash().equals(rs.getString(4))
                            && binding.executor().kind().name().equals(rs.getString(7)) && binding.executor().name().equals(rs.getString(8))
                            && binding.executor().version().equals(rs.getString(9));
                }, binding.producerRunId(), binding.producerRevision(), binding.producerChildId());
        require(matches.size() == 1 && matches.get(0), "CONSUMER_SOURCE_CHANGED");
    }
    static void checkExpectation(Binding binding, Expectation expected) {
        require(expected != null && binding.executor().equals(expected.executor()) && binding.outputContractRef().equals(expected.outputContractRef())
                && binding.requestHash().equals(expected.requestHash()) && binding.target().equals(expected.target()), "CONSUMER_EXPECTATION_MISMATCH");
    }
    static String encode(Object value) {
        try { return JSON.writeValueAsString(value); } catch (JsonProcessingException invalid) { throw failure("CONSUMER_JSON_INVALID"); }
    }
    static <T> T decode(String body, Class<T> type) {
        require(body != null && body.length() <= 65536, "CONSUMER_JSON_INVALID");
        try { return JSON.readValue(body, type); } catch (JsonProcessingException invalid) { throw failure("CONSUMER_JSON_INVALID"); }
    }
    private static Consumer readConsumer(ResultSet rs) throws SQLException {
        String body = rs.getString("expectation_json");
        require(CampaignRunStore.sha256(body).equals(rs.getString("expectation_hash")), "CONSUMER_EXPECTATION_CHANGED");
        Expectation expected = decode(body, Expectation.class);
        require(expected.stepId().equals(rs.getString("step_id")), "CONSUMER_EXPECTATION_CHANGED");
        return new Consumer(rs.getString("consumer_id"), rs.getString("binding_id"), rs.getString("run_id"), rs.getInt("revision"),
                expected, rs.getBoolean("active"));
    }
    private static Binding readBinding(ResultSet rs) throws SQLException {
        return new Binding(rs.getString("binding_id"), new Caller(rs.getString("tenant_id"), rs.getString("subject_name"), rs.getLong("auth_version")),
                rs.getString("producer_run_id"), rs.getInt("producer_revision"), rs.getString("producer_child_id"), rs.getString("producer_definition_hash"),
                rs.getString("action_id"), new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.valueOf(rs.getString("executor_kind")), rs.getString("executor_name"), rs.getString("executor_version")),
                rs.getString("output_contract_ref"), rs.getString("job_id"), rs.getString("request_id"), rs.getString("request_hash"),
                new Target(rs.getString("artifact_id"), rs.getString("scope_ref"), rs.getString("periods_ref")), rs.getLong("expires_at"),
                rs.getLong("binding_version"), CancelIntent.valueOf(rs.getString("cancel_intent")), rs.getBoolean("local_only"));
    }
    private static boolean sameFacts(Binding expected, Binding actual) {
        return new Binding(actual.bindingId(), actual.owner(), actual.producerRunId(), actual.producerRevision(), actual.producerChildId(),
                actual.producerDefinitionHash(), actual.actionId(), actual.executor(), actual.outputContractRef(), actual.jobId(), actual.requestId(),
                actual.requestHash(), actual.target(), actual.expiresAtMillis(), 1, CancelIntent.NONE, false).equals(expected);
    }
    private static void checkSource(RunToken token, Binding binding) {
        require(token.definition().caller().equals(binding.owner()) && token.definition().runId().equals(binding.producerRunId())
                && token.definition().revision() == binding.producerRevision() && token.definition().definitionHash().equals(binding.producerDefinitionHash()),
                "CONSUMER_SOURCE_CHANGED");
    }
    private static String rsString(ResultSet rs, int index) {
        try { return rs.getString(index); } catch (SQLException failure) { throw new IllegalStateException("CONSUMER_SOURCE_INVALID", failure); }
    }
    private static void transactional() { require(TransactionSynchronizationManager.isActualTransactionActive(), "CONSUMER_TRANSACTION_REQUIRED"); }
    static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
