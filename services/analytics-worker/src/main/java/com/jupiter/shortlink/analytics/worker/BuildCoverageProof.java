package com.jupiter.shortlink.analytics.worker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The original receipt proof stays unchanged; dimension content is verified independently. */
final class BuildCoverageProof {
    static final String DIMENSION_VERSION = "geo-v1";
    static final String BASE_COLUMNS = "receipt_id,payload_hash,validation_result";
    static final String DIMENSION_COLUMNS =
            BASE_COLUMNS + ",country,province,city,network,geo_status,geo_version";

    private BuildCoverageProof() {}

    static Map<String, Object> read(ClickHouseStore ch, String replica, String buildId) {
        var base = readOne(ch, replica, fingerprint(BASE_COLUMNS, "digest", buildId));
        var dimensions =
                readOne(ch, replica, fingerprint(DIMENSION_COLUMNS, "dimensionDigest", buildId));
        // The same raw receipt with two different interpretations must not be accepted merely
        // because its raw payload hash is identical. Querying any(geo) would be ambiguous.
        if (!Objects.toString(base.get("n"), "").equals(
                Objects.toString(dimensions.get("n"), "")))
            throw new IllegalStateException("CONFLICTING_BUILD_DIMENSIONS");
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("n", Objects.requireNonNull(base.get("n"), "Missing receipt count"));
        proof.put("digest", Objects.requireNonNull(base.get("digest"), "Missing receipt digest"));
        proof.put("dimensionDigest", Objects.requireNonNull(
                dimensions.get("dimensionDigest"), "Missing dimension digest"));
        proof.put("dimensionVersion", DIMENSION_VERSION);
        return proof;
    }

    static String fingerprint(String columns, String alias, String buildId) {
        return "SELECT count() n,toString(groupBitXor(cityHash64(" + columns + "))) " + alias
                + " FROM (SELECT " + columns + " FROM rebuild_input WHERE build_id="
                + ClickHouseStore.quote(buildId) + " GROUP BY " + columns + ")";
    }

    static void requireGeoVersion(ClickHouseStore ch, String replica, String buildId, String version) {
        var result = readOne(ch, replica,
                "SELECT countIf(geo_version!=" + ClickHouseStore.quote(version)
                        + ") mismatched FROM rebuild_input WHERE build_id=" + ClickHouseStore.quote(buildId)
                        + " AND kind='CLICK' AND validation_result='VALID'");
        if (Long.parseLong(Objects.toString(result.get("mismatched"), "-1")) != 0)
            throw new IllegalStateException("DIMENSION_REPLAY_VERSION_CHANGED_REQUIRES_NEW_BUILD");
    }

    private static Map<String, Object> readOne(ClickHouseStore ch, String replica, String sql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ch.query(replica, sql, rows::add);
        if (rows.size() != 1) throw new IllegalStateException("MISSING_BUILD_COVERAGE_PROOF");
        return rows.get(0);
    }
}
