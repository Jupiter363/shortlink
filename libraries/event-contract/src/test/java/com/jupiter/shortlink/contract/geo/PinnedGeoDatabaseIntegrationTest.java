package com.jupiter.shortlink.contract.geo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional offline acceptance against the project's pinned upstream release, never downloaded by tests. */
class PinnedGeoDatabaseIntegrationTest {
    @Test
    void officialPinnedDualStackDatabasesMatchTheDeclaredRegionProtocol() {
        String configured = System.getProperty("shortlink.geo.fixture-directory");
        assumeTrue(configured != null && !configured.isBlank(), "Pinned database acceptance is opt-in");
        Path directory = Path.of(configured);
        try (var lookup = Ip2RegionGeoLookup.fromEnvironment(Map.of(
                Ip2RegionGeoLookup.IPV4_PATH, directory.resolve("ip2region_v4.xdb").toString(),
                Ip2RegionGeoLookup.IPV4_SHA256, "9f9c76a8bcb234d55be3a8d2e4d828f76624470652ab33697b42e5129a1319ec",
                Ip2RegionGeoLookup.IPV6_PATH, directory.resolve("ip2region_v6.xdb").toString(),
                Ip2RegionGeoLookup.IPV6_SHA256, "98e8af04c288b16a6a70ca4d0047b54d1e7d51d99f625593f8a843ed6bad331f"))) {
            for (String fixture : new String[] {"113.92.157.29", "240e:3b7:3272:d8d0:db09:c067:8d59:539e"}) {
                GeoResult first = lookup.lookup(fixture);
                assertEquals("RESOLVED", first.status());
                assertEquals("CN", first.country());
                assertEquals("广东省", first.province());
                assertNotEquals("UNKNOWN", first.city());
                assertNotEquals("UNKNOWN", first.network());
                assertEquals(first, lookup.lookup(fixture));
            }
            assertEquals("ip2r-3.3.7-r5:9f9c76a8bcb2:98e8af04c288", lookup.version());
            assertEquals("NON_PUBLIC", lookup.lookup("127.0.0.1").status());
            assertEquals("UNKNOWN", lookup.lookup("127.0.0.1").country());
        }
    }
}
