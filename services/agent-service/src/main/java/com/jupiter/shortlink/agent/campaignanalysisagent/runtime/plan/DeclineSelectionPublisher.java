package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionPage.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded, opt-in publication adapter over actual durable statistics; not a tool or another runner. */
public final class DeclineSelectionPublisher {
    /** Invocation approval may be reconstructed for recovery without dispatching or calculating. */
    public record Prepared(ChildSpec child, Approval approval, CampaignStepExecution.LocalCall calculation) {}
    public static final String SELECTED_TYPE = "SelectedEntitiesArtifact";
    public static final String SELECTED_SCHEMA = "campaign.selected-entities/v1";
    public static final String EVIDENCE_TYPE = "DeclineEvidenceArtifact";
    public static final String EVIDENCE_SCHEMA = "campaign.decline-evidence/v1";
    private static final String IMPLEMENTATION = CampaignRunStore.sha256("decline-selection-pages/v1:verified-negative:delta-asc:link-id-asc:decimal128:observed-only");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CampaignRunStore runs;
    private final CampaignObservedLinkComparison comparison;
    private final CampaignDeclineSelectionStore selections;
    private final ArtifactAuthorizer authorizer;

    public DeclineSelectionPublisher(CampaignRunStore runs, CampaignObservedLinkComparison comparison,
                                    CampaignDeclineSelectionStore selections, ArtifactAuthorizer authorizer) {
        this.runs = Objects.requireNonNull(runs); this.comparison = Objects.requireNonNull(comparison);
        this.selections = Objects.requireNonNull(selections); this.authorizer = Objects.requireNonNull(authorizer);
    }

    /** Stable per-shard invocation, including explicit missing slots. Only this shard is materialized. */
    public CampaignDeclineSelectionStore.Receipt publishShard(CampaignStepExecution context, RunToken token,
            Definition definition, List<Period> supplied, SlotResolver resolver, int shardIndex,
            String previousChainArtifactId) throws Exception {
        context.requireCurrent();
        Prepared prepared = prepareShard(token, context.step().stepId(), definition, supplied, resolver, shardIndex, previousChainArtifactId);
        context.local(prepared.child(), prepared.approval(), authorizer, prepared.calculation());
        context.requireCurrent();
        return selections.append(token, prepared.child().childId(), authorizer);
    }

    public Prepared prepareShard(RunToken token, String stepId, Definition definition, List<Period> supplied,
                                  SlotResolver resolver, int shardIndex, String previousChainArtifactId) {
        List<Period> periods = List.copyOf(supplied);
        if (periods.size() != 2 || shardIndex < 0 || shardIndex >= Math.max(1, definition.shardCount()))
            throw new IllegalArgumentException("SELECTION_SHARD_INVALID");
        if ((shardIndex == 0) != (previousChainArtifactId == null))
            throw new IllegalArgumentException("SELECTION_PREDECESSOR_REQUIRED");
        Map<String, ArtifactMetadata> inputs = new LinkedHashMap<>();
        Artifact scope = read(token, definition.scopeArtifactId());
        inputs.put("scope", scope.metadata());
        Artifact previous = previousChainArtifactId == null ? null : read(token, previousChainArtifactId);
        if (previous != null) inputs.put("previous", previous.metadata());
        Slot[] slots = new Slot[2];
        if (definition.memberCount() > 0) {
            for (int period = 0; period < 2; period++) {
                slots[period] = resolver.resolve(period, shardIndex);
                if (slots[period] != null) inputs.put(period == 0 ? "baseline" : "target", read(token, slots[period].artifactId()).metadata());
            }
        }
        Instant expiry = expiry(inputs);
        String identity = identity(token, stepId, definition.collectionId(), Integer.toString(shardIndex));
        String pageId = "decline-page-" + identity, chainId = "decline-chain-" + identity;
        Map<String, OutputBinding> outputs = Map.of(
                "comparisonPage", new OutputBinding(pageId, DeclineSelectionPage.PAGE_TYPE, DeclineSelectionPage.PAGE_SCHEMA,
                        definition.scopeRef(), definition.periodsRef()),
                "selectionChain", new OutputBinding(chainId, DeclineSelectionPage.CHAIN_TYPE, DeclineSelectionPage.CHAIN_SCHEMA,
                        definition.scopeRef(), definition.periodsRef()));
        String parameters = json(Map.of("definition", definition, "shardIndex", shardIndex, "periods", periods,
                "baselineArtifactId", slots[0] == null ? "" : slots[0].artifactId(),
                "targetArtifactId", slots[1] == null ? "" : slots[1].artifactId(),
                "previousArtifactId", previousChainArtifactId == null ? "" : previousChainArtifactId));
        InvocationSpec invocation = new InvocationSpec("decline-selection-page", "1", IMPLEMENTATION,
                parameters, inputs, outputs, expiry);
        Approval approved = approve(invocation, payloads -> {
            Page page = DeclineSelectionPage.decode(json(payloads.get("comparisonPage")));
            Chain chain = DeclineSelectionPage.decodeChain(json(payloads.get("selectionChain")));
            return definition.equals(page.definition()) && page.shardIndex() == shardIndex
                    && chain.equals(DeclineSelectionPage.advance(page, pageId, previous));
        });
        ChildSpec child = localChild(identity, invocation);
        return new Prepared(child, approved, boundary -> {
            for (String name : inputs.keySet()) boundary.readInput(name);
            List<CampaignLinkComparability.Result> rows = new ArrayList<>(500);
            List<Gap> gaps = new ArrayList<>(2);
            comparison.compareShard(token.definition().caller(), definition.scopeArtifactId(), periods,
                    (period, shard) -> {
                        if (shard != shardIndex) throw new IllegalStateException("SELECTION_SHARD_ESCAPED");
                        return slots[period];
                    }, authorizer, definition.metric(), gaps::add, rows::add, shardIndex);
            boundary.requireCurrent();
            Page page = new Page(definition, shardIndex, rows, gaps);
            Chain chain = DeclineSelectionPage.advance(page, pageId, previous);
            return Map.of("comparisonPage", draft(outputs.get("comparisonPage"), expiry, DeclineSelectionPage.encode(page)),
                    "selectionChain", draft(outputs.get("selectionChain"), expiry, DeclineSelectionPage.encodeChain(chain)));
        });
    }

    /** Final publication reads the frozen head; the index is exposed only after durable sealing. */
    public CampaignDeclineSelectionStore.Receipt finish(CampaignStepExecution context, RunToken token,
                                                         String headArtifactId) throws Exception {
        context.requireCurrent();
        Prepared prepared = prepareFinal(token, context.step().stepId(), headArtifactId);
        context.local(prepared.child(), prepared.approval(), authorizer, prepared.calculation());
        context.requireCurrent();
        Chain chain = DeclineSelectionPage.decodeChain(read(token, headArtifactId).payloadJson());
        return selections.seal(token, chain.definition().collectionId(), prepared.child().childId(), authorizer);
    }

    public Prepared prepareFinal(RunToken token, String stepId, String headArtifactId) {
        Artifact head = read(token, headArtifactId);
        Chain chain = DeclineSelectionPage.decodeChain(head.payloadJson());
        Definition definition = chain.definition();
        if (chain.pageCount() != Math.max(1, definition.shardCount()))
            throw new IllegalArgumentException("SELECTION_PAGES_INCOMPLETE");
        Artifact scope = read(token, definition.scopeArtifactId());
        Map<String, ArtifactMetadata> inputs = Map.of("head", head.metadata(), "scope", scope.metadata());
        Instant expiry = expiry(inputs);
        String identity = identity(token, stepId, definition.collectionId(), "final");
        Map<String, OutputBinding> outputs = Map.of(
                "selectedEntities", new OutputBinding("selected-" + identity, SELECTED_TYPE, SELECTED_SCHEMA,
                        definition.scopeRef(), definition.periodsRef()),
                "selectionEvidence", new OutputBinding("decline-evidence-" + identity, EVIDENCE_TYPE, EVIDENCE_SCHEMA,
                        definition.scopeRef(), definition.periodsRef()));
        Map<String, String> payloads = Map.of("selectedEntities", manifest(chain, head.metadata().ref(), SELECTED_SCHEMA),
                "selectionEvidence", manifest(chain, head.metadata().ref(), EVIDENCE_SCHEMA));
        InvocationSpec invocation = new InvocationSpec("decline-selection-final", "1", IMPLEMENTATION,
                json(Map.of("definition", definition)), inputs, outputs, expiry);
        Approval approved = approve(invocation, values -> values.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(tree(payloads.get(entry.getKey())))));
        ChildSpec child = localChild(identity, invocation);
        return new Prepared(child, approved, boundary -> {
            boundary.readInput("head"); boundary.readInput("scope");
            return Map.of("selectedEntities", draft(outputs.get("selectedEntities"), expiry, payloads.get("selectedEntities")),
                    "selectionEvidence", draft(outputs.get("selectionEvidence"), expiry, payloads.get("selectionEvidence")));
        });
    }

    public static String manifest(Chain chain, ArtifactRef head, String schema) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", schema); manifest.put("collectionId", chain.definition().collectionId());
        manifest.put("scopeRef", chain.definition().scopeRef()); manifest.put("periodsRef", chain.definition().periodsRef());
        manifest.put("headArtifactId", head.artifactId()); manifest.put("headPayloadHash", head.payloadHash());
        manifest.put("chainHash", chain.chainHash()); manifest.put("candidateCount", chain.definition().memberCount());
        manifest.put("comparedCount", chain.totals().compared()); manifest.put("selectedCount", chain.totals().selected());
        manifest.put("selectionComplete", DeclineSelectionPage.selectionComplete(chain));
        manifest.put("emptyReason", DeclineSelectionPage.emptyReason(chain));
        return json(manifest);
    }

    private Artifact read(RunToken token, String id) { return runs.readArtifact(token.definition().caller(), id, authorizer); }

    private static ChildSpec localChild(String id, InvocationSpec invocation) {
        return new ChildSpec("decline-child-" + id, "decline-action-" + id, ChildMode.LOCAL, "decline-request-" + id, null, invocation);
    }

    private static Approval approve(InvocationSpec invocation, java.util.function.Predicate<Map<String, JsonNode>> validate) {
        Map<String, TypeContract> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
        invocation.inputs().forEach((name, metadata) -> inputs.put(name, new TypeContract(metadata.ref().type(), metadata.ref().schemaVersion())));
        invocation.outputs().forEach((name, binding) -> outputs.put(name, new TypeContract(binding.type(), binding.schemaVersion())));
        var contract = new Contract(invocation.contractName(), invocation.contractVersion(), IMPLEMENTATION, inputs, outputs,
                parameters -> parameters.equals(tree(invocation.parametersJson())), validate);
        return new LocalCalculationRegistry(List.of(contract)).approve(invocation);
    }

    private static ArtifactDraft draft(OutputBinding binding, Instant expiry, String body) {
        return new ArtifactDraft(binding.artifactId(), binding.type(), binding.schemaVersion(), binding.scopeRef(), binding.periodsRef(),
                "{\"interpretation\":\"OBSERVED_ONLY\",\"collectionCompleteness\":\"UNVERIFIED\"}",
                "{\"calculator\":\"decline-selection-pages/v1\"}", expiry, body);
    }

    private static Instant expiry(Map<String, ArtifactMetadata> inputs) {
        return inputs.values().stream().map(value -> value.ref().expiresAt()).min(Instant::compareTo).orElseThrow();
    }

    private static String identity(RunToken token, String stepId, String collection, String part) {
        return CampaignRunStore.sha256(json(List.of(token.definition().caller(), token.definition().runId(),
                token.definition().revision(), stepId, collection, part)));
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("SELECTION_JSON_INVALID", invalid); }
    }
    private static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("SELECTION_JSON_INVALID", invalid); }
    }
}
