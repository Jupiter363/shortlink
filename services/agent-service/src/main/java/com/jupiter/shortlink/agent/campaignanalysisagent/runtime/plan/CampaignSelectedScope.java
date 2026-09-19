package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore.SelectionPair;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Period;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A derived selection, never an authority enumeration or a claim of complete group membership. */
public final class CampaignSelectedScope {
    public static final String TYPE = "SelectedScopeArtifact";
    public static final String SCHEMA = "campaign.selected-scope/v1";
    public static final String OUTPUT = "selectedScope";
    private static final String IMPLEMENTATION = CampaignRunStore.sha256(
            "campaign-selected-scope/v1:sealed-pair:original-periods:verified-negative:link-id-asc:500:source-version");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final CampaignRunStore runs;
    private final CampaignDeclineSelectionStore selections;
    private final ArtifactAuthorizer authorizer;

    public record Prepared(ChildSpec child, Approval approval, CampaignStepExecution.LocalCall calculation) {}
    private record Snapshot(SelectionPair pair, Map<String, Object> manifest, List<Long> shard) {}

    public CampaignSelectedScope(CampaignRunStore runs, CampaignDeclineSelectionStore selections,
                                 ArtifactAuthorizer authorizer) {
        this.runs = Objects.requireNonNull(runs);
        this.selections = Objects.requireNonNull(selections);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    public ArtifactRef publish(CampaignStepExecution context, RunToken token, String selectedId,
                               String evidenceId, List<Period> expectedPeriods) throws Exception {
        context.requireCurrent();
        Prepared prepared = prepare(token, context.step().stepId(), selectedId, evidenceId, expectedPeriods);
        ArtifactRef result = context.local(prepared.child(), prepared.approval(), authorizer,
                prepared.calculation()).get(OUTPUT);
        context.requireCurrent();
        return result;
    }

    /** Read-only reconstruction also supplies the exact registered approval for LOCAL recovery. */
    public Prepared prepare(RunToken token, String stepId, String selectedId, String evidenceId,
                            List<Period> expectedPeriods) {
        Snapshot snapshot = snapshot(token.definition().caller(), selectedId, evidenceId,
                List.copyOf(expectedPeriods), null);
        SelectionPair pair = snapshot.pair();
        Map<String, ArtifactMetadata> inputs = Map.of("selectedEntities", pair.selectedEntities(),
                "selectionEvidence", pair.selectionEvidence(), "sourceScope", pair.scopeArtifact());
        Instant expiry = inputs.values().stream().map(value -> value.ref().expiresAt())
                .min(Instant::compareTo).orElseThrow();
        String identity = CampaignRunStore.sha256(json(List.of(token.definition().caller(), token.definition().runId(),
                token.definition().planId(), token.definition().revision(), stepId, "selected-scope")));
        OutputBinding output = new OutputBinding("selected-scope-" + identity, TYPE, SCHEMA,
                (String) snapshot.manifest().get("scopeRef"), pair.definition().periodsRef());
        String parameters = json(Map.of("selectedArtifactId", selectedId, "evidenceArtifactId", evidenceId,
                "periods", pair.periods()));
        InvocationSpec invocation = new InvocationSpec("selected-scope", "1", IMPLEMENTATION,
                parameters, inputs, Map.of(OUTPUT, output), expiry);
        Map<String, TypeContract> inputTypes = new LinkedHashMap<>();
        inputs.forEach((name, metadata) -> inputTypes.put(name,
                new TypeContract(metadata.ref().type(), metadata.ref().schemaVersion())));
        String payload = json(snapshot.manifest());
        Contract contract = new Contract(invocation.contractName(), invocation.contractVersion(), IMPLEMENTATION,
                inputTypes, Map.of(OUTPUT, new TypeContract(TYPE, SCHEMA)),
                value -> value.equals(tree(parameters)), values -> tree(payload).equals(values.get(OUTPUT)));
        Approval approval = new LocalCalculationRegistry(List.of(contract)).approve(invocation);
        ChildSpec child = new ChildSpec("selected-scope-child-" + identity, "selected-scope-action-" + identity,
                ChildMode.LOCAL, "selected-scope-request-" + identity, null, invocation);
        return new Prepared(child, approval, boundary -> {
            for (String name : inputs.keySet()) boundary.readInput(name);
            require(pair.equals(selections.inspectPair(token.definition().caller(), selectedId, evidenceId, authorizer)),
                    "SELECTED_SCOPE_SOURCE_CHANGED");
            boundary.requireCurrent();
            return Map.of(OUTPUT, new ArtifactDraft(output.artifactId(), TYPE, SCHEMA, output.scopeRef(),
                    output.periodsRef(), "{\"interpretation\":\"OBSERVED_ONLY\",\"collectionCompleteness\":\"UNVERIFIED\"}",
                    "{\"calculator\":\"campaign-selected-scope/v1\",\"enumerationVersionKind\":\"SOURCE_GROUP_MEMBERSHIP\"}",
                    expiry, payload));
        });
    }

    /** Authorized descriptor read, including genuine empty sets that cannot create a query shard. */
    public Artifact inspect(Caller caller, String selectedScopeArtifactId) {
        Artifact artifact = runs.readArtifact(caller, selectedScopeArtifactId, authorizer);
        require(TYPE.equals(artifact.metadata().ref().type()) && SCHEMA.equals(artifact.metadata().ref().schemaVersion()),
                "SELECTED_SCOPE_TYPE_INVALID");
        JsonNode manifest = tree(artifact.payloadJson());
        Snapshot snapshot = snapshot(caller, text(manifest, "selectedArtifactId"), text(manifest, "evidenceArtifactId"), null, null);
        require(manifest.equals(tree(json(snapshot.manifest())))
                && artifact.metadata().ref().scopeRef().equals(snapshot.manifest().get("scopeRef"))
                && artifact.metadata().ref().periodsRef().equals(snapshot.pair().definition().periodsRef())
                && !artifact.metadata().ref().expiresAt().isAfter(sourceExpiry(snapshot.pair()))
                && artifact.metadata().equals(runs.inspectArtifact(caller, selectedScopeArtifactId, authorizer)),
                "SELECTED_SCOPE_MANIFEST_CHANGED");
        return artifact;
    }

    /** Revalidates the sealed source and streams at most one selected page plus one requested shard. */
    public FrozenQueryScope shard(Caller caller, String selectedScopeArtifactId, int shardIndex) {
        require(shardIndex >= 0, "SELECTED_SCOPE_SHARD_UNAVAILABLE");
        Artifact artifact = runs.readArtifact(caller, selectedScopeArtifactId, authorizer);
        require(TYPE.equals(artifact.metadata().ref().type()) && SCHEMA.equals(artifact.metadata().ref().schemaVersion()),
                "SELECTED_SCOPE_TYPE_INVALID");
        JsonNode manifest = tree(artifact.payloadJson());
        Snapshot snapshot = snapshot(caller, text(manifest, "selectedArtifactId"), text(manifest, "evidenceArtifactId"),
                null, shardIndex);
        require(manifest.equals(tree(json(snapshot.manifest()))), "SELECTED_SCOPE_MANIFEST_CHANGED");
        String scopeRef = (String) snapshot.manifest().get("scopeRef");
        require(artifact.metadata().ref().scopeRef().equals(scopeRef)
                && artifact.metadata().ref().periodsRef().equals(snapshot.pair().definition().periodsRef())
                && !artifact.metadata().ref().expiresAt().isAfter(sourceExpiry(snapshot.pair())),
                "SELECTED_SCOPE_METADATA_CHANGED");
        long count = snapshot.pair().selectedCount();
        int shards = shards(count);
        require(shardIndex < shards && !snapshot.shard().isEmpty(), "SELECTED_SCOPE_SHARD_UNAVAILABLE");
        require(artifact.metadata().equals(runs.inspectArtifact(caller, selectedScopeArtifactId, authorizer)),
                "SELECTED_SCOPE_METADATA_CHANGED");
        String hash = FrozenQueryScope.memberHash(snapshot.shard());
        return new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", scopeRef,
                (String) snapshot.manifest().get("memberHash"), count,
                (String) snapshot.manifest().get("enumerationVersion"),
                FrozenQueryScope.shardIdFor(scopeRef, shardIndex, hash), shardIndex, shards, hash, snapshot.shard());
    }

    private Snapshot snapshot(Caller caller, String selectedId, String evidenceId,
                              List<Period> expectedPeriods, Integer shardIndex) {
        SelectionPair pair = selections.inspectPair(caller, selectedId, evidenceId, authorizer);
        require(expectedPeriods == null || pair.periods().equals(expectedPeriods), "SELECTED_SCOPE_PERIODS_MISMATCH");
        Artifact sourceScope = runs.readArtifact(caller, pair.scopeArtifact().ref().artifactId(), authorizer);
        require(pair.scopeArtifact().equals(sourceScope.metadata()), "SELECTED_SCOPE_SOURCE_CHANGED");
        JsonNode source = tree(sourceScope.payloadJson());
        String gid = text(source, "gid"), version = text(source, "enumerationVersion");
        require(version.matches("[a-f0-9]{64}"), "SELECTED_SCOPE_SOURCE_VERSION_INVALID");
        MessageDigest digest = digest();
        digest.update("analytics-members/v1\n".getBytes(StandardCharsets.UTF_8));
        long count = 0, previous = 0;
        long from = shardIndex == null ? -1 : (long) shardIndex * FrozenQueryScope.SHARD_SIZE;
        List<Long> selectedShard = new ArrayList<>(FrozenQueryScope.SHARD_SIZE);
        String cursor = null;
        do {
            var page = selections.readSelectedByLinkId(caller, selectedId, cursor, FrozenQueryScope.SHARD_SIZE, authorizer);
            require(page.rows().size() <= FrozenQueryScope.SHARD_SIZE, "SELECTED_SCOPE_PAGE_INVALID");
            for (CampaignLinkComparability.Result row : page.rows()) {
                require(row.linkId() > previous && row.comparability() == CampaignLinkComparability.Comparability.VERIFIED
                        && row.delta() != null && row.delta().signum() < 0, "SELECTED_SCOPE_MEMBER_INVALID");
                previous = row.linkId();
                digest.update((previous + "\n").getBytes(StandardCharsets.UTF_8));
                if (from >= 0 && count >= from && count < from + FrozenQueryScope.SHARD_SIZE) selectedShard.add(previous);
                count = Math.addExact(count, 1);
                require(count <= pair.selectedCount(), "SELECTED_SCOPE_COUNT_MISMATCH");
            }
            require(page.nextCursor() == null || (!page.rows().isEmpty() && !page.nextCursor().equals(cursor)),
                    "SELECTED_SCOPE_CURSOR_INVALID");
            cursor = page.nextCursor();
        } while (cursor != null);
        require(count == pair.selectedCount(), "SELECTED_SCOPE_COUNT_MISMATCH");
        String memberHash = HexFormat.of().formatHex(digest.digest());
        // Source artifact hashes make this a distinct derived set even if every original member was selected.
        String scopeRef = "selected-scope-" + CampaignRunStore.sha256(json(List.of(caller,
                pair.selectedEntities().ref().artifactId(), pair.selectedEntities().ref().payloadHash(),
                pair.selectionEvidence().ref().artifactId(), pair.selectionEvidence().ref().payloadHash(), memberHash)));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA); manifest.put("scopeRef", scopeRef);
        manifest.put("sourceScopeRef", pair.definition().scopeRef());
        manifest.put("sourceScopeArtifactId", sourceScope.metadata().ref().artifactId());
        manifest.put("sourceScopePayloadHash", sourceScope.metadata().ref().payloadHash());
        manifest.put("selectedArtifactId", selectedId); manifest.put("selectedPayloadHash", pair.selectedEntities().ref().payloadHash());
        manifest.put("evidenceArtifactId", evidenceId); manifest.put("evidencePayloadHash", pair.selectionEvidence().ref().payloadHash());
        manifest.put("gid", gid); manifest.put("periodsRef", pair.definition().periodsRef());
        manifest.put("periods", pair.periods()); manifest.put("memberHash", memberHash); manifest.put("memberCount", count);
        manifest.put("shardCount", shards(count)); manifest.put("enumerationVersion", version);
        manifest.put("enumerationVersionKind", "SOURCE_GROUP_MEMBERSHIP");
        manifest.put("selectionComplete", pair.selectionComplete()); manifest.put("emptyReason", pair.emptyReason());
        manifest.put("groupScopeComplete", false);
        return new Snapshot(pair, java.util.Collections.unmodifiableMap(manifest), List.copyOf(selectedShard));
    }

    private static int shards(long count) {
        return Math.toIntExact(count / FrozenQueryScope.SHARD_SIZE + (count % FrozenQueryScope.SHARD_SIZE == 0 ? 0 : 1));
    }
    private static Instant sourceExpiry(SelectionPair pair) {
        return List.of(pair.selectedEntities(), pair.selectionEvidence(), pair.scopeArtifact()).stream()
                .map(value -> value.ref().expiresAt()).min(Instant::compareTo).orElseThrow();
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String text(JsonNode value, String name) {
        require(value.path(name).isTextual() && !value.path(name).asText().isBlank(), "SELECTED_SCOPE_MANIFEST_INVALID");
        return value.path(name).asText();
    }
    private static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("SELECTED_SCOPE_JSON_INVALID", invalid); }
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("SELECTED_SCOPE_JSON_INVALID", invalid); }
    }
    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}
