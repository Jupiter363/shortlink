package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionChangeResultReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads existing immutable result pages; it never submits a query, invokes a model or calculates a new result. */
public final class CampaignArtifactReportRows {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final CampaignRunStore runs;
    private final CampaignStatisticsResultStore statistics;
    private final CampaignDeclineSelectionStore selections;
    public CampaignArtifactReportRows(CampaignRunStore runs, CampaignStatisticsResultStore statistics,
            CampaignDeclineSelectionStore selections) {
        this.runs = Objects.requireNonNull(runs); this.statistics = Objects.requireNonNull(statistics);
        this.selections = Objects.requireNonNull(selections);
    }

    public record Page(List<Map<String, Object>> rows, String nextCursor, long totalRows) {
        public Page { rows = List.copyOf(rows); }
    }

    public boolean supports(String type) {
        return List.of("StatisticsJobPages", "SelectedEntitiesArtifact", "DeclineEvidenceArtifact", "DimensionChangeArtifact").contains(type);
    }

    public Page read(Caller caller, RunToken token, ArtifactMetadata expected, String cursor, int size,
            ArtifactAuthorizer authorizer) {
        if (size < 1 || size > 500 || !caller.equals(token.definition().caller()))
            throw new IllegalArgumentException("REPORT_PAGE_INVALID");
        Artifact source = runs.readArtifact(caller, expected.ref().artifactId(), authorizer);
        if (!expected.equals(source.metadata())) throw new SecurityException("REPORT_SOURCE_CHANGED");
        JsonNode body = tree(source.payloadJson());
        String type = expected.ref().type();
        Page page;
        if ("SelectedEntitiesArtifact".equals(type) || "DeclineEvidenceArtifact".equals(type)) {
            var result = "SelectedEntitiesArtifact".equals(type)
                    ? selections.readSelectedByLinkId(caller, expected.ref().artifactId(), cursor, size, authorizer)
                    : selections.readEvidencePage(caller, expected.ref().artifactId(), cursor, size, authorizer);
            page = new Page(result.rows().stream().map(value -> row(JSON.valueToTree(value))).toList(),
                    result.nextCursor(), count(body, "SelectedEntitiesArtifact".equals(type) ? "selectedCount" : "comparedCount"));
        } else if ("StatisticsJobPages".equals(type)) {
            long offset = offset(cursor), total = count(body, "totalRows"), pageCount = count(body, "receivedPageCount");
            long expectedPages = Math.max(1, total / 500 + (total % 500 == 0 ? 0 : 1));
            if (!body.path("resultComplete").asBoolean() || offset > total || pageCount != expectedPages) throw invalid();
            List<Map<String, Object>> rows = new ArrayList<>();
            // Publication already validated the immutable chain. The native reader checks each
            // bounded page's checksum/receipt/current ACL, so a page request needs at most two pages.
            long end = Math.min(total, Math.addExact(offset, size));
            for (long pageIndex = offset / 500; pageIndex * 500 < end; pageIndex++) {
                int index = Math.toIntExact(pageIndex);
                JsonNode stored = tree(statistics.readPage(caller, expected.ref().artifactId(), index, authorizer));
                JsonNode items = stored.path("items");
                if (!items.isArray() || items.size() != Math.min(500, total - pageIndex * 500)) throw invalid();
                int from = (int) Math.max(0, offset - pageIndex * 500);
                int until = (int) Math.min(items.size(), end - pageIndex * 500);
                for (int item = from; item < until; item++) rows.add(row(items.get(item)));
            }
            if (rows.size() != end - offset) throw invalid();
            page = new Page(rows, offset + rows.size() < total ? Long.toString(offset + rows.size()) : null, total);
        } else if ("DimensionChangeArtifact".equals(type)) {
            // Existing reader proves all LOCAL producers and the complete evidence chain first.
            var verified = new DimensionChangeResultReader(runs, selections).read(token, expected, authorizer, 0);
            long offset = offset(cursor), total = count(verified.manifest(), "comparisonRows");
            if (offset > total) throw invalid();
            List<Map<String, Object>> rows = new ArrayList<>();
            String id = nullable(verified.manifest(), "headArtifactId");
            long seen = 0;
            while (id != null) {
                Artifact artifact = runs.readArtifact(caller, id, authorizer);
                JsonNode stored = tree(artifact.payloadJson());
                for (JsonNode item : stored.path("rows")) {
                    if (seen >= offset && rows.size() < size) {
                        Map<String, Object> value = new LinkedHashMap<>(row(item));
                        for (String field : List.of("ordinal", "shardIndex", "comparability", "analysisUnit", "quality"))
                            value.put(field, JSON.convertValue(stored.get(field), Object.class));
                        rows.add(java.util.Collections.unmodifiableMap(value));
                    }
                    seen++;
                }
                id = nullable(stored, "previousArtifactId");
            }
            if (seen != total) throw invalid();
            page = new Page(rows, offset + rows.size() < total ? Long.toString(offset + rows.size()) : null, total);
        } else throw new IllegalArgumentException("REPORT_ROWS_UNSUPPORTED");
        if (!expected.equals(runs.inspectArtifact(caller, expected.ref().artifactId(), authorizer)))
            throw new SecurityException("REPORT_SOURCE_CHANGED");
        return page;
    }

    private static long offset(String cursor) {
        if (cursor == null) return 0;
        if (!cursor.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(cursor); } catch (NumberFormatException invalid) { throw invalid(); }
    }
    private static long count(JsonNode value, String field) {
        JsonNode count = value.get(field);
        if (count == null || !count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0) throw invalid();
        return count.longValue();
    }
    private static String nullable(JsonNode value, String field) { return value.hasNonNull(field) ? value.path(field).textValue() : null; }
    @SuppressWarnings("unchecked") private static Map<String, Object> row(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        return java.util.Collections.unmodifiableMap(JSON.convertValue(value, LinkedHashMap.class));
    }
    static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException invalid) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("REPORT_RESULT_INVALID"); }
}
