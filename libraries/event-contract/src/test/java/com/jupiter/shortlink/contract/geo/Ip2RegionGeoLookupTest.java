package com.jupiter.shortlink.contract.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lionsoul.ip2region.xdb.Version;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Ip2RegionGeoLookupTest {
    @TempDir Path directory;

    @Test
    void noConfigurationAndNonPublicInputsRemainExplicitlyUnknown() {
        try (var lookup = Ip2RegionGeoLookup.fromEnvironment(Map.of())) {
            assertEquals("NOT_CONFIGURED", lookup.lookup("8.8.8.8").status());
            assertEquals("NON_PUBLIC", lookup.lookup("127.0.0.1").status());
            assertEquals("INVALID_IP", lookup.lookup("localhost").status());
            assertEquals("UNKNOWN", lookup.lookup("8.8.8.8").country());
            assertEquals("", lookup.version());
        }
    }

    @Test
    void explicitIncompleteMissingOrIncorrectlyPinnedConfigurationFailsAtStartup() throws Exception {
        assertCode("GEO_CONFIG_INCOMPLETE", Map.of(Ip2RegionGeoLookup.IPV4_PATH, "missing"));
        assertCode("GEO_CONFIG_INCOMPLETE", Map.of(Ip2RegionGeoLookup.MAX_CONCURRENCY, "4"));
        var environment = fixtures();
        environment.put(Ip2RegionGeoLookup.IPV4_SHA256, "bad");
        assertCode("GEO_CHECKSUM_INVALID", environment);
        environment = fixtures();
        environment.put(Ip2RegionGeoLookup.IPV4_SHA256, "0".repeat(64));
        assertCode("GEO_DATABASE_CHECKSUM_MISMATCH", environment);
        environment = fixtures();
        environment.put(Ip2RegionGeoLookup.IPV4_PATH, directory.resolve("missing.xdb").toString());
        assertCode("GEO_DATABASE_LOAD_FAILED", environment);
        environment = fixtures();
        environment.put(Ip2RegionGeoLookup.MAX_CONCURRENCY, "33");
        assertCode("GEO_CONCURRENCY_INVALID", environment);
        environment.put(Ip2RegionGeoLookup.MAX_CONCURRENCY, "not-a-number");
        assertCode("GEO_CONCURRENCY_INVALID", environment);
    }

    @Test
    void actualIpv4AndIpv6XdbLookupsArePinnedRepeatableAndConcurrencySafe() throws Exception {
        var environment = fixtures();
        var lookup = Ip2RegionGeoLookup.fromEnvironment(environment);
        String version = lookup.version();
        assertTrue(version.matches("ip2r-3\\.3\\.7-r5:[a-f0-9]{12}:[a-f0-9]{12}"));
        var expected = new GeoResult("CN", "广东省", "深圳市", "电信", "RESOLVED", version);
        assertEquals(expected, lookup.lookup("114.114.114.114"));
        assertEquals(expected, lookup.lookup("240e::1"));
        assertEquals("NOT_FOUND", lookup.lookup("8.8.8.8").status());
        assertEquals(version, lookup.lookup("10.0.0.1").version());
        assertEquals("NON_PUBLIC", lookup.lookup("::ffff:127.0.0.1").status());
        assertEquals("INVALID_IP", lookup.lookup("not-an-address").status());

        // Runtime data is the exact verified byte snapshot, even if files are later replaced.
        Files.writeString(Path.of(environment.get(Ip2RegionGeoLookup.IPV4_PATH)), "replaced");
        assertEquals(expected, lookup.lookup("114.114.114.114"));
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<GeoResult>>();
            for (int i = 0; i < 100; i++) {
                String input = i % 2 == 0 ? "114.114.114.114" : "240e::1";
                tasks.add(() -> lookup.lookup(input));
            }
            for (var result : executor.invokeAll(tasks)) assertEquals(expected, result.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            lookup.close();
        }
        lookup.close();
        assertEquals("GEO_LOOKUP_CLOSED", assertThrows(GeoLookupException.class,
                () -> lookup.lookup("114.114.114.114")).getMessage());
    }

    @Test
    void ispIsNotAccessTechnologyAndUnsupportedLegacyLayoutsFailExplicitly() {
        var result = Ip2RegionGeoLookup.parseRegion("中国|广东省|深圳市|电信|CN", "test-version");
        assertEquals("CN", result.country());
        assertEquals("广东省", result.province());
        assertEquals("深圳市", result.city());
        assertEquals("电信", result.network());
        assertEquals("UNKNOWN", Ip2RegionGeoLookup.parseRegion("Australia|Queensland|Brisbane|0|AU", "v").network());
        assertEquals("NOT_FOUND", Ip2RegionGeoLookup.parseRegion("0|0|0|0|0", "v").status());
        assertEquals("NOT_FOUND", Ip2RegionGeoLookup.parseRegion("", "v").status());
        assertEquals("GEO_DATA_FORMAT_UNSUPPORTED", assertThrows(GeoLookupException.class,
                () -> Ip2RegionGeoLookup.parseRegion("中国|0|广东省|深圳市|电信", "v")).getMessage());
        assertThrows(GeoLookupException.class, () -> Ip2RegionGeoLookup.parseRegion("中国|广\n东|深圳|电信|CN", "v"));
    }

    @Test
    void matchingChecksumDoesNotAllowCorruptDatabaseHeaders() throws Exception {
        var environment = fixtures();
        byte[] corrupt = new byte[512];
        Path path = Path.of(environment.get(Ip2RegionGeoLookup.IPV4_PATH));
        Files.write(path, corrupt);
        environment.put(Ip2RegionGeoLookup.IPV4_SHA256, sha256(corrupt));
        assertCode("GEO_DATABASE_LOAD_FAILED", environment);
    }

    private void assertCode(String code, Map<String, String> environment) {
        GeoLookupException error = assertThrows(GeoLookupException.class,
                () -> Ip2RegionGeoLookup.fromEnvironment(environment));
        assertEquals(code, error.getMessage());
        assertNull(error.getCause());
    }

    private Map<String, String> fixtures() throws Exception {
        byte[] v4 = xdb("114.114.114.114", Version.IPv4);
        byte[] v6 = xdb("240e::1", Version.IPv6);
        Path v4Path = directory.resolve("v4.xdb");
        Path v6Path = directory.resolve("v6.xdb");
        Files.write(v4Path, v4);
        Files.write(v6Path, v6);
        return new HashMap<>(Map.of(Ip2RegionGeoLookup.IPV4_PATH, v4Path.toString(),
                Ip2RegionGeoLookup.IPV4_SHA256, sha256(v4),
                Ip2RegionGeoLookup.IPV6_PATH, v6Path.toString(),
                Ip2RegionGeoLookup.IPV6_SHA256, sha256(v6),
                Ip2RegionGeoLookup.MAX_CONCURRENCY, "2"));
    }

    // A tiny genuine XDB with one synthetic record, not a production location-data fixture.
    private byte[] xdb(String input, Version version) {
        byte[] ip = IpAddressPolicy.parse(input).address();
        byte[] region = "中国|广东省|深圳市|电信|CN".getBytes(StandardCharsets.UTF_8);
        int regionOffset = 256 + 256 * 256 * 8;
        int indexOffset = regionOffset + region.length;
        byte[] content = new byte[indexOffset + version.segmentIndexSize];
        var bytes = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putShort(0, (short) 3);
        bytes.putShort(2, (short) 1);
        bytes.putInt(8, indexOffset);
        bytes.putInt(12, indexOffset);
        bytes.putShort(16, (short) version.id);
        bytes.putShort(18, (short) 4);
        int vector = 256 + ((ip[0] & 255) * 256 + (ip[1] & 255)) * 8;
        bytes.putInt(vector, indexOffset);
        bytes.putInt(vector + 4, indexOffset);
        System.arraycopy(region, 0, content, regionOffset, region.length);
        version.putBytes(content, indexOffset, ip);
        version.putBytes(content, indexOffset + version.bytes, ip);
        bytes.putShort(indexOffset + 2 * version.bytes, (short) region.length);
        bytes.putInt(indexOffset + 2 * version.bytes + 2, regionOffset);
        return content;
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
