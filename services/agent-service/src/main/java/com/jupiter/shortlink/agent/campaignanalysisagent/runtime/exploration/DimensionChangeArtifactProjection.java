package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionChangePublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionChangeResultReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/** Bounded actual joint-bucket rows with per-cohort quality, never cross-cohort UV totals. */
public final class DimensionChangeArtifactProjection implements ExplorationArtifactProjection {
    public static final String SCHEMA_VERSION = "dimension-change-artifact-projection/v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DimensionChangeResultReader reader;
    private final RunToken token;
    private final Limits limits;
    public record Limits(int previewRows, int maxProjectionBytes) {
        public Limits {
            if (previewRows < 1 || previewRows > 500 || maxProjectionBytes <= 0) throw new IllegalArgumentException("DIMENSION_PROJECTION_LIMITS_INVALID");
        }
        public static Limits defaults() { return new Limits(12, 65_536); }
    }
    public DimensionChangeArtifactProjection(CampaignRunStore runs, CampaignDeclineSelectionStore selections, RunToken token) {
        this(runs, selections, token, Limits.defaults());
    }
    public DimensionChangeArtifactProjection(CampaignRunStore runs, CampaignDeclineSelectionStore selections, RunToken token, Limits limits) {
        this.reader = new DimensionChangeResultReader(runs, selections);
        this.token = Objects.requireNonNull(token); this.limits = Objects.requireNonNull(limits);
    }
    @Override public String configurationId() {
        return SCHEMA_VERSION + ":" + DimensionChangeResultReader.VERSION + ":rows=" + limits.previewRows() + ":bytes=" + limits.maxProjectionBytes();
    }
    @Override public Map<String, Object> project(Caller current, ArtifactMetadata expected, ArtifactAuthorizer authorizer) {
        if (!DimensionChangePublisher.TYPE.equals(expected.ref().type())) return Map.of();
        if (!token.definition().caller().equals(current)) throw new SecurityException("DIMENSION_PROJECTION_CALLER_CHANGED");
        var verified = reader.read(token, expected, authorizer, limits.previewRows());
        JsonNode body = verified.manifest();
        Map<String, Object> coverage = new LinkedHashMap<>();
        for (String field : List.of("memberCount", "coveredCohorts", "pageCount", "comparisonRows", "selectionComplete", "emptyReason",
                "evidenceDisposition", "groupScopeComplete")) coverage.put(field, JSON.convertValue(body.get(field), Object.class));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("selection", "REVERSE_PAGE_CHAIN"); preview.put("returnedRows", verified.preview().size());
        preview.put("omittedRows", body.path("comparisonRows").longValue() - verified.preview().size());
        preview.put("previewComplete", body.path("comparisonRows").longValue() == verified.preview().size());
        preview.put("rows", verified.preview());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION); result.put("artifactId", expected.ref().artifactId());
        result.put("payloadHash", expected.ref().payloadHash());
        result.put("scope", Map.of("scopeRef", expected.ref().scopeRef(), "sourceScopeRef", verified.sourceScopeRef(), "periodsRef", expected.ref().periodsRef()));
        result.put("coverage", Collections.unmodifiableMap(coverage));
        try { result.put("quality", JSON.readTree(expected.qualityJson())); }
        catch (IOException invalid) { throw new IllegalStateException("DIMENSION_PROJECTION_QUALITY_INVALID", invalid); }
        result.put("preview", Collections.unmodifiableMap(preview));
        result.put("limitations", List.of("OBSERVED_ONLY", "SHARD_COHORT_NOT_INDIVIDUAL_LINK_ATTRIBUTION",
                "CROSS_QUERY_SNAPSHOTS_NOT_A_COMMON_DATABASE_SNAPSHOT", "COHORT_UV_UIP_MUST_NOT_BE_SUMMED", "SOURCE_SELECTION_QUALITY_PRESERVED"));
        try { JSON.writeValue(new ByteBudget(limits.maxProjectionBytes()), result); }
        catch (IOException excessive) { throw new IllegalStateException("DIMENSION_PROJECTION_TOO_LARGE", excessive); }
        return Collections.unmodifiableMap(result);
    }
    private static final class ByteBudget extends OutputStream {
        private final long max; private long used;
        private ByteBudget(long max) { this.max = max; }
        @Override public void write(int value) throws IOException { count(1); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { count(length); }
        private void count(int size) throws IOException {
            if (size > max - used) throw new IOException("DIMENSION_PROJECTION_TOO_LARGE"); used += size;
        }
    }
}
