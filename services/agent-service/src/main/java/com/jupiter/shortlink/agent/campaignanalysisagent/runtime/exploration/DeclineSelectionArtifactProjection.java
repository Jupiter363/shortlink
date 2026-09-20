package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Comparability;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Explanation;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Result;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionPublisher;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A bounded data view of sealed selection evidence, not a new comparison or causal assessment. */
public final class DeclineSelectionArtifactProjection implements ExplorationArtifactProjection {
    public static final String SCHEMA_VERSION = "decline-selection-artifact-projection/v1";
    private static final String INVALID = "DECLINE_SELECTION_ARTIFACT_PROJECTION_INVALID";
    private static final String TOO_LARGE = "DECLINE_SELECTION_ARTIFACT_PROJECTION_TOO_LARGE";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final CampaignRunStore runs;
    private final CampaignDeclineSelectionStore selections;
    private final Limits limits;

    public record Limits(int previewRows, int maxProjectionBytes) {
        public Limits {
            if (previewRows < 1 || previewRows > 500 || maxProjectionBytes <= 0)
                throw new IllegalArgumentException("Invalid decline selection projection limits");
        }
        public static Limits defaults() { return new Limits(12, 65_536); }
    }

    public DeclineSelectionArtifactProjection(CampaignRunStore runs, CampaignDeclineSelectionStore selections) {
        this(runs, selections, Limits.defaults());
    }

    public DeclineSelectionArtifactProjection(CampaignRunStore runs, CampaignDeclineSelectionStore selections, Limits limits) {
        this.runs = Objects.requireNonNull(runs);
        this.selections = Objects.requireNonNull(selections);
        this.limits = Objects.requireNonNull(limits);
    }

    @Override public String configurationId() {
        return SCHEMA_VERSION + ":rows=" + limits.previewRows() + ":bytes=" + limits.maxProjectionBytes();
    }

    @Override public Map<String, Object> project(Caller current, ArtifactMetadata expected, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(current); Objects.requireNonNull(expected); Objects.requireNonNull(authorizer);
        require(expected.ref() != null);
        String type = expected.ref().type();
        boolean selected = DeclineSelectionPublisher.SELECTED_TYPE.equals(type);
        if (!selected && !DeclineSelectionPublisher.EVIDENCE_TYPE.equals(type)) return Map.of();
        String schema = selected ? DeclineSelectionPublisher.SELECTED_SCHEMA : DeclineSelectionPublisher.EVIDENCE_SCHEMA;
        require(schema.equals(expected.ref().schemaVersion()));

        var actual = runs.readArtifact(current, expected.ref().artifactId(), authorizer);
        require(expected.equals(actual.metadata())
                && Objects.equals(expected.ref().payloadHash(), CampaignRunStore.sha256(actual.payloadJson())));
        ObjectNode manifest = object(actual.payloadJson());
        require(schema.equals(text(manifest.get("schemaVersion")))
                && Objects.equals(expected.ref().scopeRef(), text(manifest.get("scopeRef")))
                && Objects.equals(expected.ref().periodsRef(), text(manifest.get("periodsRef"))));
        long candidates = integer(manifest.get("candidateCount"));
        long compared = integer(manifest.get("comparedCount"));
        long selectedCount = integer(manifest.get("selectedCount"));
        boolean selectionComplete = bool(manifest.get("selectionComplete"));
        require(selectedCount <= compared && compared <= candidates && (!selectionComplete || compared == candidates));
        require(manifest.has("emptyReason"));
        String emptyReason = manifest.get("emptyReason").isNull() ? null : text(manifest.get("emptyReason"));
        require(selectedCount > 0 ? emptyReason == null
                : Set.of("NO_DECLINES", "INSUFFICIENT_EVIDENCE").contains(emptyReason == null ? "" : emptyReason));

        // These APIs verify the sealed collection, the bounded source-page chain, the real final
        // LOCAL pair and current rights. Do not guess the partner ID or load an entire result set.
        var page = selected
                ? selections.readSelectedPage(current, expected.ref().artifactId(), null, limits.previewRows(), authorizer)
                : selections.readEvidencePage(current, expected.ref().artifactId(), null, limits.previewRows(), authorizer);
        long totalRows = selected ? selectedCount : compared;
        require(page != null && page.rows().size() == Math.min(totalRows, limits.previewRows())
                && (page.nextCursor() == null) == (totalRows <= limits.previewRows()));
        List<Object> rows = new ArrayList<>();
        for (Result row : page.rows()) rows.add(row(row, selected));

        ObjectNode sourceQuality = object(expected.qualityJson());
        String interpretation = text(sourceQuality.get("interpretation"));
        require("OBSERVED_ONLY".equals(interpretation));
        String collectionCompleteness = text(sourceQuality.get("collectionCompleteness"));
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("selectionComplete", selectionComplete);
        selection.put("candidateCount", candidates);
        selection.put("comparedCount", compared);
        selection.put("selectedCount", selectedCount);
        selection.put("emptyReason", emptyReason);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("selection", selected ? "DELTA_ASC_LINK_ID_ASC" : "LINK_ID_ASC");
        preview.put("returnedRows", rows.size());
        preview.put("omittedRows", totalRows - rows.size());
        preview.put("previewComplete", totalRows == rows.size());
        preview.put("rows", List.copyOf(rows));
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("schemaVersion", SCHEMA_VERSION);
        projection.put("artifactId", expected.ref().artifactId());
        projection.put("payloadHash", expected.ref().payloadHash());
        projection.put("artifactType", type);
        // The final manifest carries opaque frozen period references, not calendar dates. Never
        // derive dates from the reference or use a latest Run revision to invent a source period.
        projection.put("scope", Map.of("scopeRef", expected.ref().scopeRef(), "periodsRef", expected.ref().periodsRef()));
        projection.put("selection", immutable(selection));
        projection.put("quality", Map.of("interpretation", interpretation, "collectionCompleteness", collectionCompleteness));
        projection.put("preview", immutable(preview));
        Map<String, Object> result = immutable(projection);
        requireSize(result);
        require(expected.equals(runs.inspectArtifact(current, expected.ref().artifactId(), authorizer)));
        return result;
    }

    private static Map<String, Object> row(Result source, boolean selected) {
        require(source != null && source.linkId() > 0 && source.metric() != null
                && source.baseline() >= 0 && source.target() >= 0 && source.delta() != null
                && source.comparability() != null && source.explanation() == Explanation.OBSERVED_ONLY);
        if (selected) require(source.comparability() == Comparability.VERIFIED && source.delta().signum() < 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("linkId", source.linkId());
        result.put("metric", source.metric().name());
        result.put("baseline", source.baseline());
        result.put("target", source.target());
        result.put("delta", source.delta());
        // Keep the genuine decimal fraction (or unknown null); no rounding or percent conversion.
        result.put("rate", source.rate());
        result.put("comparability", source.comparability().name());
        result.put("explanation", source.explanation().name());
        result.put("reasonCodes", source.reasonCodes());
        result.put("evidenceRefs", source.evidenceRefs());
        return immutable(result);
    }

    private static ObjectNode object(String encoded) {
        try {
            JsonNode value = JSON.readTree(encoded);
            require(value != null && value.isObject());
            return (ObjectNode) value;
        } catch (IOException | IllegalArgumentException invalid) { throw new IllegalStateException(INVALID); }
    }

    private static String text(JsonNode value) {
        require(value != null && value.isTextual() && !value.textValue().isBlank());
        return value.textValue();
    }

    private static long integer(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0);
        return value.longValue();
    }

    private static boolean bool(JsonNode value) {
        require(value != null && value.isBoolean());
        return value.booleanValue();
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private void requireSize(Map<String, Object> value) {
        var budget = new ByteBudget(limits.maxProjectionBytes());
        try { JSON.writeValue(budget, value); }
        catch (IOException invalid) { throw new IllegalStateException(budget.exceeded ? TOO_LARGE : INVALID); }
    }

    private static final class ByteBudget extends OutputStream {
        private final long maximum;
        private long bytes;
        private boolean exceeded;
        private ByteBudget(long maximum) { this.maximum = maximum; }
        @Override public void write(int value) throws IOException { count(1); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { count(length); }
        private void count(int length) throws IOException {
            if (length > maximum - bytes) { exceeded = true; throw new IOException(TOO_LARGE); }
            bytes += length;
        }
    }

    private static void require(boolean condition) { if (!condition) throw new IllegalStateException(INVALID); }
}
