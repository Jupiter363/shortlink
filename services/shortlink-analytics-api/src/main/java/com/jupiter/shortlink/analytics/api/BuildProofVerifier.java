package com.jupiter.shortlink.analytics.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Verifies every immutable build on one replica without one HTTP round trip per build. */
final class BuildProofVerifier {
    static final int BATCH_SIZE = 256;
    private static final String RAW_FIELDS = "receipt_id,payload_hash,validation_result";
    // This is the geo-v1 tuple verified by DimensionProof, including receipt identity.
    private static final String GEO_V1_FIELDS = RAW_FIELDS + ",country,province,city,network,geo_status,geo_version";
    private static final Map<String, Object> EMPTY_RAW = Map.of("n", 0, "digest", "0");
    private static final Map<String, Object> EMPTY_DIMENSIONS = Map.of("n", 0, "dimensionDigest", "0");
    private final ClickHouseReader ch;
    private final ObjectMapper json;

    BuildProofVerifier(ClickHouseReader ch, ObjectMapper json) {
        this.ch = ch;
        this.json = json;
    }

    void verify(String replica, List<Map<String, Object>> manifests) {
        Map<String, Map<String, Object>> expected = expected(manifests);
        List<String> builds = new ArrayList<>(expected.keySet());
        for (int start = 0; start < builds.size(); start += BATCH_SIZE) {
            List<String> batch = builds.subList(start, Math.min(start + BATCH_SIZE, builds.size()));
            Map<String, Map<String, Object>> raw = index(batch,
                    ch.query(replica, groupedSql(batch, RAW_FIELDS, "digest"), batch.size() + 1));
            for (String build : batch) {
                Map<String, Object> actual = raw.getOrDefault(build, EMPTY_RAW);
                Map<String, Object> proof = expected.get(build);
                if (!same(proof.get("n"), actual.get("n")) || !same(proof.get("digest"), actual.get("digest")))
                    throw new QueryFailure("NOT_READY", "Selected replica no longer covers the immutable build");
            }
            List<String> dimensionBuilds = batch.stream().filter(build -> DimensionProof.required(expected.get(build))).toList();
            if (!dimensionBuilds.isEmpty()) {
                Map<String, Map<String, Object>> dimensions = index(dimensionBuilds,
                        ch.query(replica, groupedSql(dimensionBuilds, GEO_V1_FIELDS, "dimensionDigest"), dimensionBuilds.size() + 1));
                for (String build : dimensionBuilds)
                    DimensionProof.verify(expected.get(build), dimensions.getOrDefault(build, EMPTY_DIMENSIONS));
            }
        }
    }

    private Map<String, Map<String, Object>> expected(List<Map<String, Object>> manifests) {
        Map<String, Map<String, Object>> expected = new LinkedHashMap<>();
        try {
            for (Map<String, Object> manifest : manifests) {
                Object id = manifest.get("build_id");
                if (id == null || id.toString().isBlank()) throw new IllegalArgumentException();
                Map<String, Object> proof = json.readValue(Objects.toString(manifest.get("coverage_proof"), "{}"),
                        new TypeReference<Map<String, Object>>() {});
                if (proof == null || proof.get("n") == null || proof.get("digest") == null)
                    throw new IllegalArgumentException();
                Map<String, Object> previous = expected.putIfAbsent(id.toString(), proof);
                if (previous != null && !previous.equals(proof))
                    throw new QueryFailure("NOT_READY", "Conflicting immutable build proofs");
            }
            return expected;
        } catch (QueryFailure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new QueryFailure("NOT_READY", "Malformed immutable build proof");
        }
    }

    private static Map<String, Map<String, Object>> index(List<String> batch, List<Map<String, Object>> rows) {
        if (rows == null || rows.size() > batch.size())
            throw new QueryFailure("NOT_READY", "Invalid immutable build proof response");
        Set<String> selected = Set.copyOf(batch);
        Map<String, Map<String, Object>> indexed = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String build = row == null ? "" : Objects.toString(row.get("build_id"), "");
            if (!selected.contains(build) || indexed.putIfAbsent(build, row) != null)
                throw new QueryFailure("NOT_READY", "Invalid immutable build proof response");
        }
        return indexed;
    }

    private static boolean same(Object expected, Object actual) {
        return expected != null && actual != null && expected.toString().equals(actual.toString());
    }

    private static String groupedSql(List<String> builds, String fields, String digest) {
        return "SELECT build_id,count() n,toString(groupBitXor(cityHash64(" + fields + "))) " + digest
                + " FROM (SELECT build_id," + fields + " FROM rebuild_input WHERE build_id IN ("
                + builds.stream().map(ClickHouseReader::quote).collect(Collectors.joining(","))
                + ") GROUP BY build_id," + fields + ") GROUP BY build_id";
    }
}
