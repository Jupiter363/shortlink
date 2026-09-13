package com.jupiter.shortlink.agent.securityriskagent.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupiter.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bounded, immutable Graph copy of persisted risk profiles. Full source cuts remain in the
 * profile repositories; these references are explanation provenance, never action authority.
 */
public final class RiskProfileGraphProjection {
    public static final int MAX_CONTEXT_BYTES = 1_048_576;
    public static final int MAX_EVIDENCE_BYTES = 32_768;
    public static final int MAX_PROFILES = 100;
    public static final String VERSION = "risk-profile-graph-v1";
    private static final JsonFactory JSON = new JsonFactory();
    private static final ObjectMapper PROFILE_READER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final List<String> META_FIELDS = List.of(
            "snapshotId", "snapshotCreatedAt", "snapshotExpiresAt", "generatedAt", "requestedStart", "requestedEnd",
            "effectiveEnd", "recoveryEpoch", "metricVersion", "detailDatasetVersion", "businessTimezone",
            "availability", "freshness", "completeness", "provisional", "collectionQuality",
            "approximation", "dimensionQuality", "dimensionQualityWindow", "missingMetrics", "missingWindows", "warnings");

    private RiskProfileGraphProjection() {}

    static Projection project(String gid, GroupRiskProfile group, List<ShortLinkRiskProfile> profiles) {
        List<ShortLinkRiskProfile> projected = projectProfiles(profiles);
        GroupRiskProfile projectedGroup = group == null ? null : new GroupRiskProfile(
                group.gid(), group.profileWindowStart(), group.profileWindowEnd(),
                group.totalShortLinksScanned(), group.lowRiskCount(), group.mediumRiskCount(),
                group.highRiskCount(), group.watchingCount(), group.disabledCount(), group.avgRiskScore(),
                group.maxRiskScore(), group.groupRiskScore(), group.groupRiskLevel(), group.groupReasonCodes(),
                projectProfiles(group.topRiskShortLinks()), group.riskTrend7d(), group.agentSummary(), group.batchId());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("gid", gid);
        context.put("groupProfile", projectedGroup);
        context.put("shortLinkProfiles", projected);
        requireBudget(context, MAX_CONTEXT_BYTES, "Risk profile Graph context");
        return new Projection(projectedGroup, projected);
    }

    private static List<ShortLinkRiskProfile> projectProfiles(List<ShortLinkRiskProfile> profiles) {
        if (profiles == null) return List.of();
        if (profiles.size() > MAX_PROFILES)
            throw new IllegalArgumentException("Risk profile Graph candidate budget exceeded");
        // The native checkpoint reader can call a record constructor with erased List<T>
        // elements during its compatibility fallback. Decode only this fixed DTO, without
        // interpreting any class-name metadata or enabling polymorphic/default typing.
        List<?> elements = profiles;
        return elements.stream().map(RiskProfileGraphProjection::typedProfile)
                .map(profile -> profile.withEvidence(projectEvidence(profile.evidence()))).toList();
    }

    private static ShortLinkRiskProfile typedProfile(Object value) {
        if (value instanceof ShortLinkRiskProfile profile) return profile;
        if (value instanceof Map<?, ?> map) return PROFILE_READER.convertValue(map, ShortLinkRiskProfile.class);
        throw new IllegalArgumentException("Invalid risk profile Graph candidate");
    }

    private static StatsEvidence projectEvidence(StatsEvidence evidence) {
        if (evidence == null) return null;
        Map<String, Object> meta = new LinkedHashMap<>();
        for (String field : META_FIELDS) {
            if (evidence.meta().containsKey(field)) meta.put(field, evidence.meta().get(field));
        }
        Object previous = evidence.meta().get("evidenceReferences");
        if (previous != null) {
            if (evidence.meta().containsKey("sourceCut") || evidence.meta().containsKey("manifestVersion"))
                throw new IllegalArgumentException("Mixed full and projected statistics provenance");
            validateReferences(previous);
            meta.put("evidenceReferences", previous);
        } else {
            Map<String, Object> references = new LinkedHashMap<>();
            references.put("version", VERSION);
            references.put("metadataSha256", sha256(evidence.meta()));
            references.put("storage", "PERSISTED_RISK_PROFILE");
            for (String field : List.of("sourceCut", "manifestVersion")) {
                if (evidence.meta().containsKey(field)) {
                    Object source = evidence.meta().get(field);
                    references.put(field, Map.of("sha256", sha256(source), "entries", entryCount(source)));
                }
            }
            meta.put("evidenceReferences", references);
        }
        // Keep quality and metric exactness intact. Oversized/invalid metadata fails closed;
        // dropping quality fields could otherwise accidentally change action decisions.
        requireBudget(meta, MAX_EVIDENCE_BYTES, "Risk profile Graph evidence");
        return new StatsEvidence(evidence.tenantId(), evidence.linkId(), meta, evidence.ruleVersion());
    }

    private static void validateReferences(Object value) {
        if (!(value instanceof Map<?, ?> refs)
                || !VERSION.equals(refs.get("version"))
                || !"PERSISTED_RISK_PROFILE".equals(refs.get("storage"))
                || !isDigest(refs.get("metadataSha256")))
            throw new IllegalArgumentException("Invalid projected statistics provenance");
        for (String field : List.of("sourceCut", "manifestVersion")) {
            if (refs.containsKey(field)
                    && (!(refs.get(field) instanceof Map<?, ?> ref)
                        || !isDigest(ref.get("sha256")) || !ref.containsKey("entries")
                        || StatsEvidence.number(ref.get("entries")) > Integer.MAX_VALUE))
                throw new IllegalArgumentException("Invalid projected statistics reference");
        }
    }

    private static boolean isDigest(Object value) {
        return value instanceof String text && text.matches("[0-9a-f]{64}");
    }

    private static int entryCount(Object source) {
        if (source instanceof Map<?, ?> map) return map.size();
        if (source instanceof Collection<?> collection) return collection.size();
        return source == null ? 0 : 1;
    }

    /** SHA-256 of sorted-key, normalized-number UTF-8 JSON; also usable to verify DB evidence. */
    public static String sha256(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DigestOutputStream(OutputStream.nullOutputStream(), digest);
                    var generator = JSON.createGenerator(output)) {
                writeJson(generator, value, 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException failure) {
            throw new IllegalArgumentException("Cannot fingerprint statistics provenance", failure);
        }
    }

    private static void requireBudget(Object value, int limit, String label) {
        try (var generator = JSON.createGenerator(new BudgetOutputStream(limit))) {
            writeJson(generator, value, 0);
        } catch (IOException failure) {
            throw new IllegalArgumentException(label + " byte budget exceeded or invalid", failure);
        }
    }

    /** Stream instead of allocating another multi-megabyte JSON string while projecting. */
    private static void writeJson(JsonGenerator out, Object value, int depth) throws IOException {
        if (depth > 32) throw new IllegalArgumentException("Risk profile Graph nesting budget exceeded");
        if (value == null) out.writeNull();
        else if (value instanceof String text) out.writeString(text);
        else if (value instanceof Boolean bool) out.writeBoolean(bool);
        else if (value instanceof Number number) out.writeNumber(new BigDecimal(number.toString()).stripTrailingZeros());
        else if (value instanceof Enum<?> item) out.writeString(item.name());
        else if (value instanceof TemporalAccessor time) out.writeString(time.toString());
        else if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key))
                    throw new IllegalArgumentException("Statistics provenance requires text keys");
                sorted.put(key, entry.getValue());
            }
            out.writeStartObject();
            for (var entry : sorted.entrySet()) {
                out.writeFieldName(entry.getKey());
                writeJson(out, entry.getValue(), depth + 1);
            }
            out.writeEndObject();
        } else if (value instanceof Collection<?> values) {
            out.writeStartArray();
            for (Object item : values) writeJson(out, item, depth + 1);
            out.writeEndArray();
        } else if (value.getClass().isRecord()) {
            out.writeStartObject();
            for (var component : value.getClass().getRecordComponents()) {
                out.writeFieldName(component.getName());
                try {
                    writeJson(out, component.getAccessor().invoke(value), depth + 1);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalArgumentException("Cannot measure risk profile Graph record", failure);
                }
            }
            out.writeEndObject();
        } else throw new IllegalArgumentException("Unsupported risk profile Graph value");
    }

    record Projection(GroupRiskProfile group, List<ShortLinkRiskProfile> profiles) {}

    private static final class BudgetOutputStream extends OutputStream {
        private final int limit;
        private long size;
        private BudgetOutputStream(int limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException { count(1); }
        @Override public void write(byte[] values, int offset, int length) throws IOException { count(length); }
        private void count(int length) throws IOException {
            size += length;
            if (size > limit) throw new IOException("Graph projection byte budget exceeded");
        }
    }
}
