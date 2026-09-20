package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import java.io.IOException;
import java.util.*;

/** Revalidates the actual LOCAL result and page chain, retaining only a bounded row preview. */
public final class DimensionChangeResultReader {
    public static final String VERSION = "dimension-change-result-reader/v1";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final CampaignRunStore runs;
    private final CampaignDeclineSelectionStore selections;

    public record Verified(Artifact artifact, JsonNode manifest, String sourceScopeRef, List<Map<String, Object>> preview) {
        public Verified { preview = List.copyOf(preview); }
    }

    public DimensionChangeResultReader(CampaignRunStore runs, CampaignDeclineSelectionStore selections) {
        this.runs = Objects.requireNonNull(runs); this.selections = Objects.requireNonNull(selections);
    }

    public Verified read(RunToken token, ArtifactMetadata expected, ArtifactAuthorizer authorizer, int previewRows) {
        require(previewRows >= 0 && previewRows <= 500);
        Artifact result = local(token, expected.ref().artifactId(), "dimensionChanges", "dimension-change-final",
                DimensionChangePublisher.TYPE, DimensionChangePublisher.SCHEMA, authorizer);
        require(expected.equals(result.metadata()));
        JsonNode body = object(result.payloadJson());
        JsonNode definition = body.path("definition");
        require(definition.isObject() && expected.ref().scopeRef().equals(text(body, "scopeRef"))
                && expected.ref().periodsRef().equals(text(body, "periodsRef"))
                && "SHARD_COHORT".equals(text(body, "analysisUnit")) && "OBSERVED_ONLY".equals(text(body, "interpretation"))
                && body.path("groupScopeComplete").isBoolean() && !body.path("groupScopeComplete").booleanValue());
        var finalChild = runs.child(token, expected.childId()).orElseThrow();
        require(object(finalChild.spec().localInvocation().parametersJson()).path("definition").equals(definition));
        Artifact scope = new CampaignSelectedScope(runs, selections, authorizer).inspect(token.definition().caller(),
                text(definition, "selectedScopeArtifactId"));
        JsonNode scopeBody = object(scope.payloadJson());
        require(scope.metadata().equals(finalChild.spec().localInvocation().inputs().get("selectedScope"))
                && expected.ref().scopeRef().equals(scope.metadata().ref().scopeRef())
                && expected.ref().periodsRef().equals(scope.metadata().ref().periodsRef())
                && expected.ref().periodsRef().equals(text(definition, "periodsRef"))
                && body.path("periods").equals(scopeBody.path("periods"))
                && number(body, "memberCount") == number(scopeBody, "memberCount")
                && number(body, "coveredCohorts") == number(scopeBody, "shardCount")
                && body.path("selectionComplete").isBoolean()
                && body.path("selectionComplete").equals(scopeBody.path("selectionComplete"))
                && body.has("emptyReason") && body.get("emptyReason").equals(scopeBody.get("emptyReason")));
        long members = number(body, "memberCount"), pages = number(body, "pageCount"), rows = number(body, "comparisonRows");
        String disposition = text(body, "evidenceDisposition");
        require(members > 0 ? "OBSERVED".equals(disposition) && pages > 0
                : pages == 0 && rows == 0 && ("NO_DECLINES".equals(body.path("emptyReason").asText())
                    ? "NOT_APPLICABLE".equals(disposition) : "INSUFFICIENT_EVIDENCE".equals(disposition)));
        String id = nullable(body, "headArtifactId"), hash = nullable(body, "headPayloadHash");
        require((pages == 0) == (id == null) && (id == null) == (hash == null));
        List<Map<String, Object>> preview = new ArrayList<>();
        long expectedOrdinal = pages - 1, observedRows = 0;
        boolean head = true;
        while (id != null) {
            require(expectedOrdinal >= 0);
            Artifact page = local(token, id, "dimensionPage", "dimension-change-page", DimensionChangePublisher.PAGE_TYPE,
                    DimensionChangePublisher.PAGE_SCHEMA, authorizer);
            require(hash.equals(page.metadata().ref().payloadHash()) && expected.ref().scopeRef().equals(page.metadata().ref().scopeRef())
                    && expected.ref().periodsRef().equals(page.metadata().ref().periodsRef()));
            JsonNode pageBody = object(page.payloadJson());
            require(definition.equals(pageBody.path("definition")) && number(pageBody, "ordinal") == expectedOrdinal--
                    && "SHARD_COHORT".equals(text(pageBody, "analysisUnit"))
                    && "OBSERVED_ONLY".equals(text(pageBody, "interpretation"))
                    && pageBody.path("rows").isArray() && pageBody.path("rows").size() <= 500);
            var pageChild = runs.child(token, page.metadata().childId()).orElseThrow();
            var inputs = pageChild.spec().localInvocation().inputs();
            require(scope.metadata().equals(inputs.get("selectedScope"))
                    && object(pageChild.spec().localInvocation().parametersJson()).path("definition").equals(definition));
            for (String side : List.of("baseline", "target")) {
                ArtifactMetadata source = inputs.get(side);
                require(source != null && source.ref().artifactId().equals(text(pageBody, side + "ArtifactId"))
                        && source.ref().payloadHash().equals(text(pageBody, side + "PayloadHash")));
            }
            if (head) {
                require(page.metadata().equals(finalChild.spec().localInvocation().inputs().get("head")));
                head = false;
            }
            observedRows = Math.addExact(observedRows, pageBody.path("rows").size());
            for (JsonNode row : pageBody.path("rows")) if (preview.size() < previewRows) {
                Map<String, Object> sample = new LinkedHashMap<>();
                for (String field : List.of("ordinal", "shardIndex", "analysisUnit", "comparability", "quality", "cohortSummary")) {
                    require(pageBody.has(field)); sample.put(field, JSON.convertValue(pageBody.get(field), Object.class));
                }
                sample.put("row", JSON.convertValue(row, Object.class));
                preview.add(Collections.unmodifiableMap(sample));
            }
            id = nullable(pageBody, "previousArtifactId"); hash = nullable(pageBody, "previousPayloadHash");
            require((id == null) == (hash == null));
            ArtifactMetadata previous = inputs.get("previous");
            require(id == null ? previous == null : previous != null && id.equals(previous.ref().artifactId())
                    && hash.equals(previous.ref().payloadHash()));
        }
        require(expectedOrdinal == -1 && observedRows == rows);
        require(expected.equals(runs.inspectArtifact(token.definition().caller(), expected.ref().artifactId(), authorizer)));
        return new Verified(result, body, text(scopeBody, "sourceScopeRef"), preview);
    }

    private Artifact local(RunToken token, String id, String output, String contract, String type, String schema,
                           ArtifactAuthorizer authorizer) {
        Artifact artifact = runs.readArtifact(token.definition().caller(), id, authorizer);
        ArtifactMetadata metadata = artifact.metadata();
        var definition = token.definition();
        require(definition.caller().equals(metadata.owner()) && definition.runId().equals(metadata.runId())
                && definition.planId().equals(metadata.planId()) && definition.revision() == metadata.revision()
                && type.equals(metadata.ref().type()) && schema.equals(metadata.ref().schemaVersion())
                && CampaignRunStore.sha256(artifact.payloadJson()).equals(metadata.ref().payloadHash()));
        ChildRecord child = runs.child(token, metadata.childId()).orElseThrow();
        require(child.spec().mode() == ChildMode.LOCAL && child.state() == ChildState.READY && !child.callbackActive()
                && child.reason() == null && child.spec().localInvocation() != null
                && contract.equals(child.spec().localInvocation().contractName())
                && "1".equals(child.spec().localInvocation().contractVersion())
                && DimensionChangePublisher.implementationId().equals(child.spec().localInvocation().implementationHash())
                && child.spec().actionId().equals(metadata.actionId())
                && metadata.ref().equals(runs.localOutputs(token, metadata.childId(), authorizer).get(output)));
        JsonNode payload = object(artifact.payloadJson()), quality = object(metadata.qualityJson());
        require(schema.equals(text(payload, "schemaVersion")) && "OBSERVED_ONLY".equals(text(quality, "interpretation")));
        return artifact;
    }

    private static JsonNode object(String json) {
        try { JsonNode node = JSON.readTree(json); require(node != null && node.isObject()); return node; }
        catch (IOException invalid) { throw new IllegalStateException("DIMENSION_RESULT_INVALID", invalid); }
    }
    private static String text(JsonNode node, String field) {
        require(node.path(field).isTextual() && !node.path(field).textValue().isBlank()); return node.path(field).textValue();
    }
    private static String nullable(JsonNode node, String field) {
        require(node.has(field)); return node.get(field).isNull() ? null : text(node, field);
    }
    private static long number(JsonNode node, String field) {
        require(node.path(field).isIntegralNumber() && node.path(field).canConvertToLong() && node.path(field).longValue() >= 0);
        return node.path(field).longValue();
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalStateException("DIMENSION_RESULT_INVALID"); }
}
