package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.Receipt;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Local release preflight and facts only; shares the consumer gate without issuing HTTP or cleanup. */
public final class JdbcCampaignStatisticsReleaseStore implements CampaignStatisticsReleaseStore {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CampaignRunStore runs;
    private final CampaignStatisticsResultStore results;
    private final JdbcStatisticsConsumerGate consumers;

    public JdbcCampaignStatisticsReleaseStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this(jdbc, transactions, clock, Limits.defaults(), 8 * 1024 * 1024);
    }

    public JdbcCampaignStatisticsReleaseStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                               Limits limits, int pageBytes) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Release requires one writable REQUIRED DataSource transaction");
        runs = new JdbcCampaignRunStore(jdbc, transactions, clock, limits);
        results = new JdbcCampaignStatisticsResultStore(jdbc, transactions, clock, limits, pageBytes);
        consumers = new JdbcStatisticsConsumerGate(jdbc, clock);
    }

    @Override
    public Intent prepare(RunToken token, String childId, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(authorizer);
        return transaction(() -> {
            // The shared gate locks all revisions in order before the physical job binding.
            // Old schemas retain their single-producer contract and cannot expose adoption.
            var shared = consumers.schemaAvailable() ? consumers.lockForRelease(token, childId) : null;
            lockRun(token, true);
            ChildRecord initial = child(token, childId);
            require(initial.jobId() != null, "RELEASE_JOB_REQUIRED");
            String bindingId = bindingId(token.definition().caller(), initial.jobId());
            Optional<Stored> existing = binding(bindingId);
            lockChild(token, childId);
            ChildRecord child = child(token, childId);
            require(Objects.equals(initial.jobId(), child.jobId()), "RELEASE_BINDING_CHANGED");
            if (existing.isPresent()) requireProducer(existing.get(), token, childId);
            requireSoleProducer(token, child);
            require(child.state() == ChildState.READY && child.spec().mode() == ChildMode.ASYNC
                    && child.artifactId() != null, "RELEASE_LOCAL_RESULT_NOT_READY");
            require(StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH.equals(child.spec().wire().path()), "RELEASE_PROTOCOL_UNAVAILABLE");
            jdbc.queryForList("SELECT child_id FROM campaign_statistics_receipt WHERE run_id=? AND revision=? AND child_id=? FOR UPDATE",
                    token.definition().runId(), token.definition().revision(), childId);
            Receipt receipt = results.receipt(token, childId)
                    .orElseThrow(() -> failure("RELEASE_RECEIPT_MISSING"));
            Artifact artifact = verifyLocalCopy(token, child, receipt, authorizer);
            Intent expected = new Intent(bindingId, token.definition().runId(), token.definition().revision(), childId,
                    child.jobId(), child.spec().requestId(), child.spec().wire().hash(), child.artifactId(),
                    artifact.metadata().ref().payloadHash(), receipt.chainHash(), receipt.spec().expiresAtMillis(), 1, State.REQUESTED);
            if (existing.isPresent()) {
                requireSameBinding(expected, existing.get().intent());
                if (shared != null) consumers.markLocalOnly(shared.bindingId());
                return existing.get().intent();
            }
            Caller owner = token.definition().caller();
            jdbc.update("INSERT INTO campaign_statistics_release (binding_id,tenant_id,subject_name,auth_version,producer_run_id,"
                            + "revision,child_id,job_id,request_id,request_hash,artifact_id,artifact_hash,chain_hash,expires_at,"
                            + "binding_version,release_state,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,'REQUESTED',?)",
                    bindingId, owner.tenantId(), owner.subject(), owner.authVersion(), expected.producerRunId(), expected.revision(),
                    childId, expected.jobId(), expected.requestId(), expected.requestHash(), expected.artifactId(),
                    expected.artifactHash(), expected.chainHash(), expected.expiresAtMillis(), clock.millis());
            // This change and REQUESTED commit together. Adoption can now depend only on the
            // just-verified complete local Artifact; no live consumer may still require pages.
            if (shared != null) consumers.markLocalOnly(shared.bindingId());
            return expected;
        });
    }

    @Override
    public Optional<Intent> intent(RunToken token, String childId) {
        return transaction(() -> {
            lockRun(token, false);
            return jdbc.query("SELECT * FROM campaign_statistics_release WHERE producer_run_id=? AND revision=? AND child_id=? FOR UPDATE",
                            (rs, row) -> stored(rs), token.definition().runId(), token.definition().revision(), childId).stream().findFirst()
                    .map(stored -> { requireProducer(stored, token, childId); return stored.intent(); });
        });
    }

    @Override
    public boolean mayRelease(DispatchPermit permit, Intent expected) {
        try {
            return transaction(() -> {
                var shared = consumers.schemaAvailable() ? consumers.lockForRelease(permit.token(), permit.childId()) : null;
                lockRun(permit.token(), true);
                Stored stored = requireBinding(permit, expected);
                if (expected.state() != State.REQUESTED || stored.intent().state() != State.REQUESTED
                        || clock.millis() >= stored.intent().expiresAtMillis()) return false;
                if (shared != null && !shared.localOnly()) return false;
                ReleaseAttempt attempt = exactAttempt(permit, stored.intent());
                requireSoleProducer(permit.token(), child(permit.token(), permit.childId()));
                return attempt.callbackActive() && runs.mayDispatch(permit);
            });
        } catch (IllegalArgumentException | IllegalStateException | SecurityException denied) {
            return false;
        }
    }

    @Override
    public void confirm(DispatchPermit permit, Intent expected, long remoteExpiresAt) {
        transaction(() -> {
            if (consumers.schemaAvailable()) consumers.lockForReleaseFact(permit);
            lockRun(permit.token(), false); // Cancellation fences new I/O, not an already dispatched remote fact.
            Stored stored = requireBinding(permit, expected);
            ReleaseAttempt attempt = exactAttempt(permit, stored.intent());
            require(remoteExpiresAt == stored.intent().expiresAtMillis(), "RELEASE_EXPIRY_MISMATCH");
            if (stored.intent().state() == State.CONFIRMED) return null;
            require(attempt.callbackActive(), "RELEASE_CALLBACK_NOT_ACTIVE");
            int changed = jdbc.update("UPDATE campaign_statistics_release SET release_state='CONFIRMED',confirmed_at=? "
                            + "WHERE binding_id=? AND binding_version=? AND release_state='REQUESTED'",
                    clock.millis(), expected.bindingId(), expected.version());
            require(changed == 1, "RELEASE_INTENT_CHANGED");
            return null;
        });
    }

    private Artifact verifyLocalCopy(RunToken token, ChildRecord child, Receipt receipt, ArtifactAuthorizer authorizer) {
        require(receipt.published() && receipt.complete() && receipt.nextPageIndex() == receipt.requiredPages()
                && receipt.snapshotJson() != null && receipt.metricsJson() != null, "RELEASE_LOCAL_RESULT_NOT_READY");
        var spec = receipt.spec();
        require(child.jobId().equals(spec.jobId()) && child.artifactId().equals(spec.artifactId())
                && child.spec().wire().hash().equals(spec.requestHash()), "RELEASE_RECEIPT_MISMATCH");
        if (clock.millis() >= spec.expiresAtMillis()) throw new SecurityException("RELEASE_LOCAL_RESULT_EXPIRED");
        Artifact artifact = runs.readArtifact(token.definition().caller(), child.artifactId(), authorizer);
        ArtifactMetadata metadata = artifact.metadata();
        ArtifactRef ref = metadata.ref();
        var definition = token.definition();
        require(definition.caller().equals(metadata.owner()) && definition.runId().equals(metadata.runId())
                && definition.revision() == metadata.revision() && definition.planId().equals(metadata.planId())
                && child.spec().childId().equals(metadata.childId()) && child.spec().actionId().equals(metadata.actionId()),
                "RELEASE_ARTIFACT_PRODUCER_MISMATCH");
        String executorVersion = jdbc.queryForObject("SELECT executor_version FROM campaign_action_ledger WHERE run_id=? "
                        + "AND revision=? AND action_id=?", String.class, definition.runId(), definition.revision(), child.spec().actionId());
        require(Objects.equals(executorVersion, metadata.executorVersion())
                && CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(ref.type())
                && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(ref.schemaVersion())
                && spec.scopeRef().equals(ref.scopeRef()) && spec.periodsRef().equals(ref.periodsRef())
                && ref.expiresAt().toEpochMilli() == spec.expiresAtMillis(), "RELEASE_ARTIFACT_CONTRACT_MISMATCH");
        JsonNode snapshot = json(receipt.snapshotJson());
        JsonNode manifest = json(artifact.payloadJson());
        JsonNode provenance = json(metadata.provenanceJson());
        require(snapshot.equals(json(metadata.qualityJson())) && snapshot.equals(manifest.path("meta"))
                && json(receipt.metricsJson()).equals(manifest.path("metrics"))
                && spec.jobId().equals(manifest.path("jobId").asText()) && spec.artifactId().equals(manifest.path("artifactId").asText())
                && number(manifest.path("pageCount"), spec.pageCount()) && number(manifest.path("receivedPageCount"), receipt.storedPages())
                && number(manifest.path("totalRows"), spec.totalRows()) && receipt.chainHash().equals(manifest.path("chainHash").asText())
                && manifest.path("resultComplete").isBoolean() && manifest.path("resultComplete").booleanValue(), "RELEASE_MANIFEST_MISMATCH");
        StatisticsJobResultProtocol protocol = new StatisticsJobResultProtocol(child);
        Map<String, Object> proof = protocol.frozenScopeProof(spec.scopeRef(), object(snapshot));
        require("FROZEN_SET".equals(provenance.path("scopeMode").asText())
                && spec.jobId().equals(provenance.path("jobId").asText())
                && spec.requestHash().equals(provenance.path("requestHash").asText())
                && persistedJson(proof).equals(provenance.path("scopeProof")), "RELEASE_SCOPE_PROOF_MISMATCH");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_page WHERE run_id=? AND revision=? AND child_id=?",
                Long.class, definition.runId(), definition.revision(), child.spec().childId());
        require(count != null && count == receipt.requiredPages(), "RELEASE_PAGE_COUNT_MISMATCH");
        String specHash = jdbc.queryForObject("SELECT spec_hash FROM campaign_statistics_receipt WHERE run_id=? AND revision=? AND child_id=?",
                String.class, definition.runId(), definition.revision(), child.spec().childId());
        String chain = CampaignRunStore.sha256(CampaignStatisticsResultStore.SCHEMA_VERSION + ":" + specHash);
        var status = new StatisticsJobResultProtocol.Status(spec.jobId(), "SUCCEEDED", spec.totalRows(), spec.pageCount(), spec.expiresAtMillis(), null);
        for (int index = 0; index < receipt.requiredPages(); index++) {
            // One bounded page at a time. readPage verifies bytes, checksum, expiry and current grant.
            String payload = results.readPage(definition.caller(), spec.artifactId(), index, authorizer);
            var page = protocol.page(status, object(json(payload)), index);
            require(snapshot.equals(json(page.snapshotJson())) && json(receipt.metricsJson()).equals(json(page.metricsJson())),
                    "RELEASE_PAGE_CONTEXT_MISMATCH");
            chain = CampaignRunStore.sha256(chain + ":" + index + ":" + CampaignRunStore.sha256(payload) + ":" + page.rowCount());
        }
        require(chain.equals(receipt.chainHash()), "RELEASE_PAGE_CHAIN_MISMATCH");
        require(metadata.equals(runs.inspectArtifact(definition.caller(), child.artifactId(), authorizer)), "RELEASE_ARTIFACT_CHANGED");
        return artifact;
    }

    private Stored requireBinding(DispatchPermit permit, Intent expected) {
        Objects.requireNonNull(expected);
        require(expected.state() != null, "RELEASE_INTENT_CHANGED");
        require(permit.purpose() == DispatchPurpose.RELEASE, "RELEASE_ATTEMPT_REQUIRED");
        Stored stored = binding(expected.bindingId()).orElseThrow(() -> failure("RELEASE_INTENT_MISSING"));
        requireProducer(stored, permit.token(), permit.childId());
        requireSameBinding(expected, stored.intent());
        return stored;
    }

    private ReleaseAttempt exactAttempt(DispatchPermit permit, Intent intent) {
        // This intentionally avoids runs.child(oldToken): cancellation changes the Run writer,
        // but an exact dispatch row still proves an already-sent callback's historical identity.
        // mayRelease calls the current Run gate before reaching this same narrow row check.
        var rows = jdbc.query("SELECT tenant_id,child_state,callback_active,job_id,artifact_id,request_id,wire_hash "
                        + "FROM campaign_child_ledger WHERE run_id=? AND revision=? AND child_id=? AND attempt_id=? "
                        + "AND attempt_version=? AND attempt_purpose='RELEASE' AND dispatch_run_version=? AND dispatch_run_token=? FOR UPDATE",
                (rs, row) -> new ReleaseAttempt(rs.getString("tenant_id"), rs.getString("child_state"), rs.getBoolean("callback_active"),
                        rs.getString("job_id"), rs.getString("artifact_id"), rs.getString("request_id"), rs.getString("wire_hash")),
                permit.token().definition().runId(), permit.token().definition().revision(), permit.childId(),
                permit.attemptId(), permit.attemptVersion(), permit.token().version(), permit.token().advanceToken());
        require(rows.size() == 1, "RELEASE_ATTEMPT_FENCED");
        ReleaseAttempt attempt = rows.get(0);
        require(permit.token().definition().caller().tenantId().equals(attempt.tenantId()) && "READY".equals(attempt.childState())
                && intent.jobId().equals(attempt.jobId()) && intent.artifactId().equals(attempt.artifactId())
                && intent.requestId().equals(attempt.requestId()) && intent.requestHash().equals(attempt.requestHash()), "RELEASE_ATTEMPT_FENCED");
        return attempt;
    }

    private void requireSoleProducer(RunToken token, ChildRecord child) {
        Caller owner = token.definition().caller();
        Integer other = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger c JOIN campaign_run_ledger r "
                        + "ON r.run_id=c.run_id AND r.revision=c.revision WHERE r.tenant_id=? AND r.subject_name=? AND c.job_id=? "
                        + "AND NOT (c.run_id=? AND c.revision=? AND c.child_id=?)", Integer.class,
                owner.tenantId(), owner.subject(), child.jobId(), token.definition().runId(), token.definition().revision(), child.spec().childId());
        require(other != null && other == 0, "RELEASE_MULTIPLE_PRODUCERS_UNSUPPORTED");
    }

    private void lockRun(RunToken token, boolean current) {
        Objects.requireNonNull(token);
        RunDefinition definition = Objects.requireNonNull(token.definition());
        var rows = jdbc.queryForList("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,run_status,"
                + "row_version,advance_token FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE", definition.runId(), definition.revision());
        require(rows.size() == 1, "RUN_NOT_FOUND");
        Map<String, Object> row = rows.get(0);
        Caller owner = definition.caller();
        if (!owner.tenantId().equals(row.get("tenant_id")) || !owner.subject().equals(row.get("subject_name"))
                || owner.authVersion() != ((Number) row.get("auth_version")).longValue()) throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
        require(definition.sessionId().equals(row.get("session_id")) && definition.planId().equals(row.get("plan_id"))
                && definition.definitionHash().equals(row.get("definition_hash")), "RUN_DEFINITION_CHANGED");
        if (current) require("ACTIVE".equals(row.get("run_status")) && token.version() == ((Number) row.get("row_version")).longValue()
                && token.advanceToken().equals(row.get("advance_token")), "RUN_TOKEN_FENCED");
    }

    private void lockChild(RunToken token, String childId) {
        require(jdbc.queryForList("SELECT child_id FROM campaign_child_ledger WHERE run_id=? AND revision=? AND child_id=? FOR UPDATE",
                token.definition().runId(), token.definition().revision(), childId).size() == 1, "CHILD_NOT_FOUND");
    }
    private ChildRecord child(RunToken token, String childId) {
        return runs.child(token, childId).orElseThrow(() -> failure("CHILD_NOT_FOUND"));
    }
    private Optional<Stored> binding(String bindingId) {
        return jdbc.query("SELECT * FROM campaign_statistics_release WHERE binding_id=? FOR UPDATE",
                (rs, row) -> stored(rs), bindingId).stream().findFirst();
    }
    private static Stored stored(ResultSet rs) throws SQLException {
        return new Stored(new Caller(rs.getString("tenant_id"), rs.getString("subject_name"), rs.getLong("auth_version")),
                new Intent(rs.getString("binding_id"), rs.getString("producer_run_id"), rs.getInt("revision"), rs.getString("child_id"),
                        rs.getString("job_id"), rs.getString("request_id"), rs.getString("request_hash"), rs.getString("artifact_id"),
                        rs.getString("artifact_hash"), rs.getString("chain_hash"), rs.getLong("expires_at"), rs.getLong("binding_version"),
                        State.valueOf(rs.getString("release_state"))));
    }
    private static void requireProducer(Stored stored, RunToken token, String childId) {
        if (!stored.owner().equals(token.definition().caller())) throw new SecurityException("RELEASE_SUBJECT_MISMATCH");
        require(stored.intent().producerRunId().equals(token.definition().runId()) && stored.intent().revision() == token.definition().revision()
                && stored.intent().childId().equals(childId), "RELEASE_PRODUCER_CONFLICT");
    }
    private static void requireSameBinding(Intent expected, Intent actual) {
        Intent comparable = new Intent(actual.bindingId(), actual.producerRunId(), actual.revision(), actual.childId(), actual.jobId(),
                actual.requestId(), actual.requestHash(), actual.artifactId(), actual.artifactHash(), actual.chainHash(), actual.expiresAtMillis(),
                actual.version(), expected.state());
        require(expected.equals(comparable), "RELEASE_INTENT_CHANGED");
    }
    private static String bindingId(Caller owner, String jobId) {
        try { return "release-" + CampaignRunStore.sha256(JSON.writeValueAsString(List.of("statistics-release/v1", owner.tenantId(), owner.subject(), jobId))); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode json(String value) {
        try { JsonNode parsed = JSON.readTree(value); require(parsed != null && parsed.isObject(), "RELEASE_INVALID_JSON"); return parsed; }
        catch (JsonProcessingException invalid) { throw failure("RELEASE_INVALID_JSON"); }
    }
    private static Map<String, Object> object(JsonNode value) { return JSON.convertValue(value, new TypeReference<>() {}); }
    private static JsonNode persistedJson(Object value) {
        try { return json(JSON.writeValueAsString(value)); }
        catch (JsonProcessingException invalid) { throw failure("RELEASE_INVALID_JSON"); }
    }
    private static boolean number(JsonNode value, long expected) { return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == expected; }
    private <T> T transaction(Supplier<T> work) {
        try { return transactions.execute(status -> work.get()); }
        catch (DataIntegrityViolationException conflict) { throw failure("RELEASE_BINDING_CONFLICT"); }
    }
    private static void require(boolean valid, String reason) { if (!valid) throw failure(reason); }
    private static IllegalStateException failure(String reason) { return new IllegalStateException(reason); }
    private record Stored(Caller owner, Intent intent) { }
    private record ReleaseAttempt(String tenantId, String childState, boolean callbackActive, String jobId,
                                  String artifactId, String requestId, String requestHash) { }
}
