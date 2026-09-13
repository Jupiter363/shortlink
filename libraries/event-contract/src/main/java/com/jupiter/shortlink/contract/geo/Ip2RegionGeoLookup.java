package com.jupiter.shortlink.contract.geo;

import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Immutable XDB snapshot shared by asynchronous interpretation and explicit replay.
 * The official 3.3.7 data format is country|province|city|ISP|ISO-country-code.
 * https://github.com/lionsoul2014/ip2region/blob/master/binding/java/README.md
 */
public final class Ip2RegionGeoLookup implements GeoLookup {
    public static final String IPV4_PATH = "ANALYTICS_GEO_IPV4_XDB_PATH";
    public static final String IPV4_SHA256 = "ANALYTICS_GEO_IPV4_XDB_SHA256";
    public static final String IPV6_PATH = "ANALYTICS_GEO_IPV6_XDB_PATH";
    public static final String IPV6_SHA256 = "ANALYTICS_GEO_IPV6_XDB_SHA256";
    public static final String MAX_CONCURRENCY = "ANALYTICS_GEO_MAX_CONCURRENCY";
    private static final int MAX_XDB_BYTES = 128 * 1024 * 1024;
    private final String version;
    private final Semaphore permits;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private Ip2Region database;

    private Ip2RegionGeoLookup(Ip2Region database, String version, int concurrency) {
        this.database = database;
        this.version = version;
        this.permits = new Semaphore(concurrency, true);
    }

    public static GeoLookup fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static GeoLookup fromEnvironment(Map<String, String> environment) {
        String[] keys = {IPV4_PATH, IPV4_SHA256, IPV6_PATH, IPV6_SHA256};
        boolean configured = false;
        for (String key : keys) configured |= present(environment.get(key));
        if (!configured) {
            if (present(environment.get(MAX_CONCURRENCY))) throw new GeoLookupException("GEO_CONFIG_INCOMPLETE");
            return GeoLookup.notConfigured();
        }
        for (String key : keys) {
            if (!present(environment.get(key))) throw new GeoLookupException("GEO_CONFIG_INCOMPLETE");
        }
        int concurrency;
        try {
            concurrency = Integer.parseInt(environment.getOrDefault(MAX_CONCURRENCY, "4"));
        } catch (NumberFormatException ex) {
            throw new GeoLookupException("GEO_CONCURRENCY_INVALID");
        }
        if (concurrency < 1 || concurrency > 32) throw new GeoLookupException("GEO_CONCURRENCY_INVALID");
        String v4Hash = checksum(environment.get(IPV4_SHA256));
        String v6Hash = checksum(environment.get(IPV6_SHA256));
        // Hash the exact bytes supplied to the parser, avoiding file replacement between validation and use.
        Config v4 = config(environment.get(IPV4_PATH), v4Hash, true);
        Config v6 = config(environment.get(IPV6_PATH), v6Hash, false);
        try {
            var database = Ip2Region.create(v4, v6);
            return new Ip2RegionGeoLookup(database,
                    "ip2r-3.3.7-r5:" + v4Hash.substring(0, 12) + ":" + v6Hash.substring(0, 12), concurrency);
        } catch (Exception ex) {
            throw new GeoLookupException("GEO_DATABASE_INIT_FAILED");
        }
    }

    private static Config config(String path, String expectedHash, boolean ipv4) {
        try (InputStream file = Files.newInputStream(Path.of(path))) {
            byte[] content = file.readNBytes(MAX_XDB_BYTES + 1);
            if (content.length < 256 || content.length > MAX_XDB_BYTES) {
                throw new GeoLookupException("GEO_DATABASE_SIZE_INVALID");
            }
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(expectedHash), HexFormat.of().parseHex(actualHash))) {
                throw new GeoLookupException("GEO_DATABASE_CHECKSUM_MISMATCH");
            }
            try (var input = new ByteArrayInputStream(content)) {
                var builder = Config.custom().setCachePolicy(Config.BufferCache)
                        .setCacheSliceBytes(1024 * 1024).setXdbInputStream(input);
                return ipv4 ? builder.asV4() : builder.asV6();
            }
        } catch (GeoLookupException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GeoLookupException("GEO_DATABASE_LOAD_FAILED");
        }
    }

    @Override
    public GeoResult lookup(String ip) {
        var parsed = IpAddressPolicy.parse(ip);
        lifecycle.readLock().lock();
        try {
            if (database == null) throw new GeoLookupException("GEO_LOOKUP_CLOSED");
            if (parsed.disposition() == IpAddressPolicy.Disposition.NON_PUBLIC) {
                return GeoResult.unknown("NON_PUBLIC", version);
            }
            if (parsed.disposition() == IpAddressPolicy.Disposition.INVALID) {
                return GeoResult.unknown("INVALID_IP", version);
            }
            boolean acquired = false;
            try {
                permits.acquire();
                acquired = true;
                return parseRegion(database.search(parsed.address()), version);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new GeoLookupException("GEO_LOOKUP_INTERRUPTED");
            } catch (GeoLookupException ex) {
                throw ex;
            } catch (Exception ex) {
                // Vendor errors can contain input/data details; expose only a stable safe code.
                throw new GeoLookupException("GEO_LOOKUP_FAILED");
            } finally {
                if (acquired) permits.release();
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    static GeoResult parseRegion(String region, String version) {
        if (region == null || region.isBlank()) return GeoResult.unknown("NOT_FOUND", version);
        String[] fields = region.split("\\|", -1);
        if (fields.length != 5 || !(fields[4].matches("[A-Z]{2}") || "0".equals(fields[4]))) {
            throw new GeoLookupException("GEO_DATA_FORMAT_UNSUPPORTED");
        }
        for (String field : fields) {
            if (field.length() > 256 || field.chars().anyMatch(Character::isISOControl)) {
                throw new GeoLookupException("GEO_DATA_FORMAT_UNSUPPORTED");
            }
        }
        var result = new GeoResult(fields[4], fields[1], fields[2], fields[3], "RESOLVED", version);
        if ("UNKNOWN".equals(result.country()) && "UNKNOWN".equals(result.province())
                && "UNKNOWN".equals(result.city()) && "UNKNOWN".equals(result.network())) {
            return GeoResult.unknown("NOT_FOUND", version);
        }
        return result;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (database != null) {
                try {
                    database.close();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new GeoLookupException("GEO_CLOSE_INTERRUPTED");
                } finally {
                    database = null;
                }
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private static String checksum(String value) {
        if (!value.matches("[0-9a-fA-F]{64}")) throw new GeoLookupException("GEO_CHECKSUM_INVALID");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
