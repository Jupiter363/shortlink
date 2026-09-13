package com.jupiter.shortlink.analytics.api;

import java.util.Map;
import java.util.Objects;

/** Optional versioned dimension proof complements the immutable raw receipt proof. */
public final class DimensionProof {
    private static final String FIELDS = "receipt_id,payload_hash,validation_result,country,province,city,network,geo_status,geo_version";
    private DimensionProof() {}
    public static String sql(String build) {
        return "SELECT count() n,toString(groupBitXor(cityHash64(" + FIELDS
                + "))) dimensionDigest FROM (SELECT " + FIELDS
                + " FROM rebuild_input WHERE build_id=" + ClickHouseReader.quote(build)
                + " GROUP BY " + FIELDS + ")";
    }
    public static boolean required(Map<String, Object> proof) {
        return proof.containsKey("dimensionDigest") || proof.containsKey("dimensionVersion");
    }
    public static void verify(Map<String, Object> expected, Map<String, Object> actual) {
        if (!"geo-v1".equals(expected.get("dimensionVersion"))
                || expected.get("dimensionDigest") == null
                || !Objects.toString(expected.get("n")).equals(Objects.toString(actual.get("n")))
                || !Objects.toString(expected.get("dimensionDigest"))
                        .equals(Objects.toString(actual.get("dimensionDigest"))))
            throw new QueryFailure("NOT_READY", "Frozen geography dimensions fail their versioned proof");
    }
}
