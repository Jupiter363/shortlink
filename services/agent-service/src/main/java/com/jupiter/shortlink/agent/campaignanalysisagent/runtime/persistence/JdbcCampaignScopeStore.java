package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignScope;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent durable membership pages. No HTTP, worker, scheduler or native checkpoint state. */
public final class JdbcCampaignScopeStore implements CampaignScopeStore {
    private static final String PATH = "/internal/short-link-admin/v1/agent-tools/authorization/group-members-page";
    private static final String TYPE = "ScopeArtifact";
    private static final String SCHEMA = "campaign-scope/v1";
    private static final String PERIODS = "scope-enumeration";
    private static final int PAGE_BYTES = 128 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CampaignRunStore runs;

    public JdbcCampaignScopeStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                 CampaignRunStore runs) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.runs = Objects.requireNonNull(runs);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly() || !(runs instanceof JdbcCampaignRunStore ledger)
                || !ledger.sharesTransactionDataSource(jdbc))
            throw new IllegalArgumentException("Scope and run stores require one writable REQUIRED DataSource transaction");
    }

    @Override public Collection prepare(RunToken token, Definition supplied) {
        Definition definition = normalize(supplied);
        return transaction(() -> {
            requireRun(token);
            principal(token.definition().caller());
            if (!clock.instant().isBefore(definition.expiresAt())) fail("SCOPE_COLLECTION_EXPIRED");
            Optional<Stored> previous = find(definition.collectionId(), true);
            if (previous.isPresent()) {
                owned(token, previous.get());
                if (!previous.get().value().definition().equals(definition)) fail("SCOPE_DEFINITION_CHANGED");
                return previous.get().value();
            }
            runs.prepareAction(token, definition.action());
            jdbc.update("INSERT INTO campaign_scope_collection (collection_id,run_id,revision,action_id,gid,definition_hash,"
                            + "expires_at,collection_state,page_count,member_count,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,'COLLECTING',0,0,?,?)", definition.collectionId(),
                    token.definition().runId(), token.definition().revision(), definition.action().actionId(), definition.gid(),
                    definitionHash(definition), definition.expiresAt().toEpochMilli(), clock.millis(), clock.millis());
            return required(definition.collectionId(), true).value();
        });
    }

    @Override public Collection load(RunToken token, String collectionId) {
        id(collectionId);
        return transaction(() -> {
            requireRun(token);
            Stored stored = required(collectionId, true);
            owned(token, stored);
            return stored.value();
        });
    }

    @Override public Collection commitPage(DispatchPermit permit, String collectionId, GroupMembersPage page) {
        Objects.requireNonNull(permit);
        Objects.requireNonNull(page);
        id(collectionId);
        return transaction(() -> {
            requireRun(permit.token());
            Stored stored = required(collectionId, true);
            owned(permit.token(), stored);
            ChildRecord child = exactChild(permit);
            Collection current = stored.value();
            if (child.spec().mode() != ChildMode.SYNC || child.jobId() != null
                    || !child.spec().actionId().equals(current.definition().action().actionId())
                    || (permit.purpose() != DispatchPurpose.FRESH && permit.purpose() != DispatchPurpose.AUTHORITY_PAGE_READ))
                fail("SCOPE_CHILD_BINDING_INVALID");
            GroupMembersPage.Request request = request(child.spec().wire());
            if (!request.gid().equals(current.definition().gid())) fail("SCOPE_CHILD_BINDING_INVALID");
            if (!stored.owner().tenantId().equals(page.tenantId()) || !stored.owner().subject().equals(page.subjectId())
                    || stored.owner().authVersion() != page.authVersion() || !request.gid().equals(page.gid())
                    || !Objects.equals(request.afterLinkId(), page.afterLinkId())) fail("SCOPE_PAGE_IDENTITY_MISMATCH");
            String body = encode(page.asMap(), PAGE_BYTES);
            String checksum = CampaignRunStore.sha256(body);
            List<Integer> accepted = jdbc.queryForList("SELECT page_index FROM campaign_scope_page "
                            + "WHERE collection_id=? AND run_id=? AND revision=? AND child_id=? FOR UPDATE", Integer.class,
                    collectionId, stored.runId(), stored.revision(), permit.childId());
            if (!accepted.isEmpty()) {
                Page saved = readPage(stored, accepted.get(0), false);
                if (child.state() != ChildState.READY || !checksum.equals(saved.hash())
                        || !child.spec().wire().hash().equals(saved.wireHash())
                        || !Objects.equals(child.artifactId(), saved.page().nextCursor() == null
                                ? current.artifactId() : pageArtifact(collectionId, accepted.get(0))))
                    fail("SCOPE_PAGE_CONFLICT");
                if (!clock.instant().isBefore(current.definition().expiresAt())) {
                    if (current.state() == State.COLLECTING) return invalidateStored(stored, "SCOPE_COLLECTION_EXPIRED");
                    fail("SCOPE_COLLECTION_EXPIRED");
                }
                return current;
            }
            if (current.state() != State.COLLECTING) fail("SCOPE_COLLECTION_NOT_COLLECTING");
            if (!child.callbackActive() || child.state() != ChildState.DISPATCHING || !runs.mayDispatch(permit))
                fail("SCOPE_ATTEMPT_NOT_DISPATCHING");
            if (!clock.instant().isBefore(current.definition().expiresAt()))
                return invalidateStored(stored, "SCOPE_COLLECTION_EXPIRED");
            if ((request.ownershipVersion() != null && !request.ownershipVersion().equals(page.ownershipVersion()))
                    || (current.enumerationVersion() != null && !current.enumerationVersion().equals(page.ownershipVersion())))
                return invalidateStored(stored, "QUERY_SCOPE_CHANGED");
            page.requireMatches(request, stored.owner().tenantId(), stored.owner().subject(), stored.owner().authVersion());
            if (!Objects.equals(request.afterLinkId(), current.nextCursor())
                    || !Objects.equals(request.ownershipVersion(), current.enumerationVersion())) fail("SCOPE_PAGE_NOT_CONTIGUOUS");
            int ordinal = current.pageCount();
            if (ordinal == 0 && request.afterLinkId() != null) fail("SCOPE_PAGE_NOT_CONTIGUOUS");
            jdbc.update("INSERT INTO campaign_scope_page (collection_id,run_id,revision,page_index,child_id,wire_hash,"
                            + "payload_json,payload_hash,row_count,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    collectionId, stored.runId(), stored.revision(), ordinal, permit.childId(), child.spec().wire().hash(),
                    body, checksum, page.linkIds().size(), clock.millis());
            jdbc.update("UPDATE campaign_scope_collection SET enumeration_version=?,next_cursor=?,page_count=?,member_count=?,"
                            + "updated_at=? WHERE collection_id=?", page.ownershipVersion(), page.nextCursor(),
                    Math.addExact(ordinal, 1), Math.addExact(current.memberCount(), page.linkIds().size()), clock.millis(), collectionId);
            Stored updated = required(collectionId, true);
            if (page.nextCursor() != null) {
                String payload = encode(Map.of("schemaVersion", "campaign-scope-page-ref/v1", "collectionId", collectionId,
                        "pageIndex", ordinal, "payloadHash", checksum), PAGE_BYTES);
                runs.publishReady(permit, new ArtifactDraft(pageArtifact(collectionId, ordinal), "ScopePageRef",
                        "campaign-scope-page-ref/v1", collectionRef(collectionId), PERIODS,
                        quality(false), provenance(updated), current.definition().expiresAt(), payload));
            } else {
                FrozenCampaignScope.Summary summary = summarize(updated, false);
                String artifactId = scopeArtifact(collectionId);
                runs.publishReady(permit, new ArtifactDraft(artifactId, TYPE, SCHEMA, summary.scopeRef(), PERIODS,
                        quality(true), provenance(updated), current.definition().expiresAt(), manifest(updated, summary)));
                jdbc.update("UPDATE campaign_scope_collection SET collection_state='PUBLISHED',artifact_id=?,updated_at=? "
                        + "WHERE collection_id=?", artifactId, clock.millis(), collectionId);
            }
            return required(collectionId, true).value();
        });
    }

    @Override public Collection invalidate(RunToken token, String collectionId, String code) {
        id(collectionId);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid scope failure code");
        return transaction(() -> {
            requireRun(token);
            Stored stored = required(collectionId, true);
            owned(token, stored);
            return invalidateStored(stored, code);
        });
    }

    @Override public FrozenQueryScope shard(Caller current, String artifactId, int shardIndex, ArtifactAuthorizer authorizer) {
        if (shardIndex < 0) throw new IllegalArgumentException("SCOPE_SHARD_UNAVAILABLE");
        Artifact artifact = runs.readArtifact(current, artifactId, authorizer);
        if (!TYPE.equals(artifact.metadata().ref().type()) || !SCHEMA.equals(artifact.metadata().ref().schemaVersion()))
            fail("SCOPE_ARTIFACT_CONTRACT_MISMATCH");
        FrozenQueryScope result = transaction(() -> {
            // Match mutation lock order even when reading an artifact from a completed/cancelled run.
            List<String> lockedRun = jdbc.queryForList("SELECT run_id FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                    String.class, artifact.metadata().runId(), artifact.metadata().revision());
            if (lockedRun.size() != 1) fail("RUN_NOT_FOUND");
            List<String> ids = jdbc.queryForList("SELECT collection_id FROM campaign_scope_collection WHERE artifact_id=?",
                    String.class, artifactId);
            if (ids.size() != 1) fail("SCOPE_ARTIFACT_NOT_PUBLISHED");
            Stored stored = required(ids.get(0), true);
            Collection collection = stored.value();
            ArtifactMetadata metadata = artifact.metadata();
            if (collection.state() != State.PUBLISHED || !current.equals(stored.owner())
                    || !metadata.runId().equals(stored.runId()) || metadata.revision() != stored.revision()
                    || !metadata.actionId().equals(collection.definition().action().actionId())
                    || !metadata.executorVersion().equals(collection.definition().action().executorVersion())
                    || !scopeArtifact(collection.definition().collectionId()).equals(artifactId)
                    || !metadata.ref().expiresAt().equals(collection.definition().expiresAt())
                    || !PERIODS.equals(metadata.ref().periodsRef())) fail("SCOPE_ARTIFACT_BINDING_INVALID");
            if (!clock.instant().isBefore(collection.definition().expiresAt())) fail("SCOPE_COLLECTION_EXPIRED");
            FrozenCampaignScope.Summary summary = summarize(stored, true);
            if (!manifest(stored, summary).equals(artifact.payloadJson())
                    || !summary.scopeRef().equals(metadata.ref().scopeRef())) fail("SCOPE_ARTIFACT_CORRUPTED");
            if (!metadata.childId().equals(readPage(stored, collection.pageCount() - 1, true).childId()))
                fail("SCOPE_ARTIFACT_BINDING_INVALID");
            if (summary.memberCount() == 0 || shardIndex >= summary.shardCount())
                throw new IllegalArgumentException("SCOPE_SHARD_UNAVAILABLE");
            List<Long> members = readPage(stored, shardIndex, true).page().linkIds();
            String memberHash = FrozenQueryScope.memberHash(members);
            return new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", summary.scopeRef(), summary.memberHash(),
                    summary.memberCount(), summary.enumerationVersion(),
                    FrozenQueryScope.shardIdFor(summary.scopeRef(), shardIndex, memberHash), shardIndex,
                    summary.shardCount(), memberHash, members);
        });
        runs.inspectArtifact(current, artifactId, authorizer);
        return result;
    }

    private Collection invalidateStored(Stored stored, String code) {
        if (stored.value().state() == State.PUBLISHED) fail("SCOPE_ALREADY_PUBLISHED");
        if (stored.value().state() == State.INVALID) return stored.value();
        jdbc.update("UPDATE campaign_scope_collection SET collection_state='INVALID',failure_code=?,updated_at=? "
                + "WHERE collection_id=?", code, clock.millis(), stored.value().definition().collectionId());
        return required(stored.value().definition().collectionId(), true).value();
    }

    private FrozenCampaignScope.Summary summarize(Stored stored, boolean published) {
        String collectionId = stored.value().definition().collectionId();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_scope_page WHERE collection_id=?", Long.class, collectionId);
        if (count == null || count != stored.value().pageCount()) fail("SCOPE_PAGE_COUNT_MISMATCH");
        Iterable<FrozenCampaignScope.AuthorityPage> pages = () -> new Iterator<>() {
            int ordinal;
            @Override public boolean hasNext() { return ordinal < stored.value().pageCount(); }
            @Override public FrozenCampaignScope.AuthorityPage next() {
                if (!hasNext()) throw new NoSuchElementException();
                GroupMembersPage page = readPage(stored, ordinal++, published).page();
                return new FrozenCampaignScope.AuthorityPage(principal(stored.owner()), page.gid(), page.afterLinkId(),
                        page.ownershipVersion(), page.linkIds(), page.nextCursor());
            }
        };
        FrozenCampaignScope.Summary summary = FrozenCampaignScope.summarize(principal(stored.owner()), stored.value().definition().gid(), pages);
        if (summary.pageCount() != stored.value().pageCount() || summary.memberCount() != stored.value().memberCount()
                || !summary.enumerationVersion().equals(stored.value().enumerationVersion()) || stored.value().nextCursor() != null)
            fail("SCOPE_SUMMARY_MISMATCH");
        return summary;
    }

    private Page readPage(Stored stored, int ordinal, boolean published) {
        var rows = jdbc.query("SELECT p.payload_json,p.payload_hash,p.wire_hash,p.row_count,p.child_id,c.action_id,"
                        + "c.wire_hash AS child_wire_hash,c.child_state,c.artifact_id FROM campaign_scope_page p "
                        + "JOIN campaign_child_ledger c ON c.run_id=p.run_id AND c.revision=p.revision AND c.child_id=p.child_id "
                        + "WHERE p.collection_id=? AND p.run_id=? AND p.revision=? AND p.page_index=?", (rs, row) -> {
            String body = rs.getString("payload_json");
            bytes(body, PAGE_BYTES);
            String hash = CampaignRunStore.sha256(body);
            if (!hash.equals(rs.getString("payload_hash")) || !rs.getString("wire_hash").equals(rs.getString("child_wire_hash"))
                    || !stored.value().definition().action().actionId().equals(rs.getString("action_id"))) fail("SCOPE_PAGE_CORRUPTED");
            GroupMembersPage page = decode(body, GroupMembersPage.class, PAGE_BYTES);
            if (page.linkIds().size() != rs.getInt("row_count") || !stored.owner().tenantId().equals(page.tenantId())
                    || !stored.owner().subject().equals(page.subjectId()) || stored.owner().authVersion() != page.authVersion()
                    || !stored.value().definition().gid().equals(page.gid())) fail("SCOPE_PAGE_CORRUPTED");
            if (published || ordinal < stored.value().pageCount() - 1) {
                String artifactId = page.nextCursor() == null ? stored.value().artifactId()
                        : pageArtifact(stored.value().definition().collectionId(), ordinal);
                if (!"READY".equals(rs.getString("child_state")) || !Objects.equals(artifactId, rs.getString("artifact_id")))
                    fail("SCOPE_PAGE_RECEIPT_MISSING");
            }
            return new Page(page, hash, rs.getString("wire_hash"), rs.getString("child_id"));
        }, stored.value().definition().collectionId(), stored.runId(), stored.revision(), ordinal);
        if (rows.size() != 1) fail("SCOPE_PAGE_MISSING");
        return rows.get(0);
    }

    private ChildRecord exactChild(DispatchPermit permit) {
        var rows = jdbc.queryForList("SELECT dispatch_run_version,dispatch_run_token FROM campaign_child_ledger "
                        + "WHERE run_id=? AND revision=? AND child_id=? FOR UPDATE", permit.token().definition().runId(),
                permit.token().definition().revision(), permit.childId());
        if (rows.size() != 1 || !(rows.get(0).get("dispatch_run_version") instanceof Number version)
                || version.longValue() != permit.token().version()
                || !permit.token().advanceToken().equals(rows.get(0).get("dispatch_run_token"))) fail("SCOPE_ATTEMPT_FENCED");
        ChildRecord child = runs.child(permit.token(), permit.childId()).orElseThrow();
        if (!Objects.equals(child.attemptId(), permit.attemptId()) || child.attemptVersion() != permit.attemptVersion()
                || child.purpose() != permit.purpose()) fail("SCOPE_ATTEMPT_FENCED");
        return child;
    }

    private void requireRun(RunToken token) {
        Objects.requireNonNull(token);
        var definition = token.definition();
        var rows = jdbc.queryForList("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,run_status,"
                + "row_version,advance_token FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                definition.runId(), definition.revision());
        if (rows.size() != 1) fail("RUN_NOT_FOUND");
        Map<String, Object> row = rows.get(0);
        if (!definition.caller().tenantId().equals(row.get("tenant_id")) || !definition.caller().subject().equals(row.get("subject_name"))
                || definition.caller().authVersion() != ((Number) row.get("auth_version")).longValue())
            throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
        if (!definition.sessionId().equals(row.get("session_id")) || !definition.planId().equals(row.get("plan_id"))
                || !definition.definitionHash().equals(row.get("definition_hash"))) fail("RUN_DEFINITION_CHANGED");
        if (!"ACTIVE".equals(row.get("run_status")) || token.version() != ((Number) row.get("row_version")).longValue()
                || !token.advanceToken().equals(row.get("advance_token"))) fail("RUN_TOKEN_FENCED");
    }

    private Stored required(String collectionId, boolean lock) {
        return find(collectionId, lock).orElseThrow(() -> new IllegalStateException("SCOPE_COLLECTION_NOT_FOUND"));
    }

    private Optional<Stored> find(String collectionId, boolean lock) {
        return jdbc.query("SELECT s.*,a.step_id,a.executor_kind,a.executor_name,a.executor_version,a.definition_json AS action_json,"
                        + "a.definition_hash AS action_hash,r.tenant_id,r.subject_name,r.auth_version FROM campaign_scope_collection s "
                        + "JOIN campaign_action_ledger a ON a.run_id=s.run_id AND a.revision=s.revision AND a.action_id=s.action_id "
                        + "JOIN campaign_run_ledger r ON r.run_id=s.run_id AND r.revision=s.revision WHERE s.collection_id=?"
                        + (lock ? " FOR UPDATE" : ""), (rs, row) -> {
            String actionJson = rs.getString("action_json");
            bytes(actionJson, Limits.defaults().definitionBytes());
            if (!CampaignRunStore.sha256(actionJson).equals(rs.getString("action_hash"))) fail("ACTION_DEFINITION_CORRUPTED");
            ActionSpec action = new ActionSpec(rs.getString("action_id"), rs.getString("step_id"), rs.getString("executor_kind"),
                    rs.getString("executor_name"), rs.getString("executor_version"), actionJson);
            Definition definition = normalize(new Definition(collectionId, action, rs.getString("gid"),
                    Instant.ofEpochMilli(rs.getLong("expires_at"))));
            if (!definitionHash(definition).equals(rs.getString("definition_hash"))) fail("SCOPE_DEFINITION_CORRUPTED");
            Long cursor = rs.getObject("next_cursor") == null ? null : rs.getLong("next_cursor");
            Collection value = new Collection(definition, State.valueOf(rs.getString("collection_state")),
                    rs.getString("enumeration_version"), cursor, rs.getInt("page_count"), rs.getLong("member_count"),
                    rs.getString("artifact_id"), rs.getString("failure_code"));
            validateHeader(value);
            return new Stored(value, rs.getString("run_id"), rs.getInt("revision"),
                    new Caller(rs.getString("tenant_id"), rs.getString("subject_name"), rs.getLong("auth_version")));
        }, collectionId).stream().findFirst();
    }

    private static void owned(RunToken token, Stored stored) {
        if (!token.definition().caller().equals(stored.owner())) throw new SecurityException("SCOPE_OWNER_MISMATCH");
        if (!token.definition().runId().equals(stored.runId()) || token.definition().revision() != stored.revision())
            fail("SCOPE_RUN_MISMATCH");
    }

    private static Definition normalize(Definition value) {
        Objects.requireNonNull(value);
        id(value.collectionId());
        Objects.requireNonNull(value.action());
        new GroupMembersPage.Request(value.gid(), null, null);
        Objects.requireNonNull(value.expiresAt());
        return new Definition(value.collectionId(), value.action(), value.gid(), Instant.ofEpochMilli(value.expiresAt().toEpochMilli()));
    }

    private static void validateHeader(Collection value) {
        if (value.pageCount() < 0 || value.memberCount() < 0 || value.memberCount() > (long) value.pageCount() * 500
                || (value.enumerationVersion() != null && !value.enumerationVersion().matches("[a-f0-9]{64}"))
                || (value.pageCount() == 0 && (value.enumerationVersion() != null || value.nextCursor() != null || value.memberCount() != 0))
                || (value.pageCount() > 0 && value.enumerationVersion() == null)
                || (value.nextCursor() != null && value.nextCursor() <= 0)
                || (value.state() == State.PUBLISHED && (value.pageCount() < 1 || value.nextCursor() != null || value.artifactId() == null)))
            fail("SCOPE_HEADER_CORRUPTED");
    }

    private static String definitionHash(Definition value) {
        ActionSpec action = value.action();
        return CampaignRunStore.sha256(encode(List.of(value.collectionId(), action.actionId(), action.stepId(), action.executorKind(),
                action.executorName(), action.executorVersion(), action.definitionHash(), value.gid(), value.expiresAt().toEpochMilli()),
                Limits.defaults().definitionBytes()));
    }

    private static String manifest(Stored stored, FrozenCampaignScope.Summary summary) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA); manifest.put("collectionId", stored.value().definition().collectionId());
        manifest.put("gid", summary.gid()); manifest.put("scopeRef", summary.scopeRef()); manifest.put("memberHash", summary.memberHash());
        manifest.put("enumerationVersion", summary.enumerationVersion()); manifest.put("memberCount", summary.memberCount());
        manifest.put("pageCount", summary.pageCount()); manifest.put("shardCount", summary.shardCount());
        return encode(manifest, PAGE_BYTES);
    }

    private static String quality(boolean complete) {
        return encode(Map.of("resultComplete", complete, "membership", complete ? "COMPLETE" : "PARTIAL",
                "historicalSnapshot", false), PAGE_BYTES);
    }

    private static String provenance(Stored stored) {
        return encode(Map.of("source", "CURRENT_AUTHORITY", "collectionId", stored.value().definition().collectionId(),
                "enumerationVersion", stored.value().enumerationVersion(), "historicalSnapshot", false), PAGE_BYTES);
    }

    private static GroupMembersPage.Request request(WireRequest wire) {
        if (!"POST".equals(wire.method()) || !PATH.equals(wire.path())) fail("SCOPE_CHILD_BINDING_INVALID");
        return decode(wire.bodyJson(), GroupMembersPage.Request.class, Limits.defaults().requestBytes());
    }

    private static String scopeArtifact(String collection) { return "scope-artifact-" + CampaignRunStore.sha256(collection); }
    private static String pageArtifact(String collection, int ordinal) { return "scope-page-ref-" + CampaignRunStore.sha256(collection + ":" + ordinal); }
    private static String collectionRef(String collection) { return "scope-collection-" + CampaignRunStore.sha256(collection); }
    private static AgentPrincipal principal(Caller owner) { return new AgentPrincipal(owner.tenantId(), owner.subject(), owner.authVersion(), false); }
    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")) throw new IllegalArgumentException("Invalid scope collection identity");
    }

    private static String encode(Object value, int limit) {
        try { String result = JSON.writeValueAsString(value); bytes(result, limit); return result; }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("SCOPE_JSON_INVALID"); }
    }
    private static <T> T decode(String value, Class<T> type, int limit) {
        bytes(value, limit);
        try { return JSON.readValue(value, type); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("SCOPE_JSON_INVALID"); }
    }
    private static void bytes(String value, int limit) {
        if (value == null) fail("SCOPE_JSON_MISSING");
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { bytes += 4; i++; }
            else bytes += Character.isSurrogate(c) ? 1 : 3;
            if (bytes > limit) fail("SCOPE_JSON_TOO_LARGE");
        }
    }
    private <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static void fail(String code) { throw new IllegalStateException(code); }
    private record Stored(Collection value, String runId, int revision, Caller owner) {}
    private record Page(GroupMembersPage page, String hash, String wireHash, String childId) {}
}
