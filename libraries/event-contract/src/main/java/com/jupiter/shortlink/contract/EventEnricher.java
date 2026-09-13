package com.jupiter.shortlink.contract;

import com.jupiter.shortlink.contract.geo.GeoLookup;
import com.jupiter.shortlink.contract.geo.GeoResult;
import com.jupiter.shortlink.contract.geo.Ip2RegionGeoLookup;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared deterministic interpretation for Flink and archive rebuild. No wall clock or network I/O.
 */
public final class EventEnricher implements AutoCloseable {
    public static final String DATASET = "detail-v1";
    public static final String PARSER = "builtin-ua-v1";
    public static final String HASH = "hmac-sha256-128-v1";
    private final byte[] key;
    private final long futureTolerance;
    private final GeoLookup geoLookup;

    public EventEnricher(String key, long futureTolerance) {
        this(key, futureTolerance, GeoLookup.notConfigured());
    }

    public EventEnricher(String key, long futureTolerance, GeoLookup geoLookup) {
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("ANALYTICS_HASH_KEY must contain at least 32 bytes");
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.futureTolerance = futureTolerance;
        this.geoLookup = java.util.Objects.requireNonNull(geoLookup, "geoLookup");
    }

    public static EventEnricher fromEnvironment(String key, long futureTolerance) {
        var geo = Ip2RegionGeoLookup.fromEnvironment();
        try {
            return new EventEnricher(key, futureTolerance, geo);
        } catch (RuntimeException ex) {
            geo.close();
            throw ex;
        }
    }

    @Override
    public void close() {
        geoLookup.close();
    }

    public String geoVersion() {
        return geoLookup.version();
    }

    public EnrichedRecord enrich(RawReceipt r) {
        Object event;
        try {
            if (Topics.CLICK_RAW.equals(r.topic()))
                event = EventJson.read(r.payload(), ClickEventV1.class);
            else if (Topics.GATEWAY_REQUEST.equals(r.topic()))
                event = EventJson.read(r.payload(), GatewayRequestEventV1.class);
            else return rejected(r, "UNKNOWN_TOPIC");
        } catch (IllegalArgumentException ex) {
            return rejected(r, "INVALID_SCHEMA");
        }
        // Lookup errors are infrastructure failures and must reach the consumer's retry boundary.
        if (event instanceof ClickEventV1 click) return click(r, click);
        return request(r, (GatewayRequestEventV1) event);
    }

    private EnrichedRecord click(RawReceipt r, ClickEventV1 e) {
        String result = TimeValidation.validate(r, e.occurredAt(), futureTolerance);
        if (e.schemaVersion() != 1
                || blank(e.eventId())
                || blank(e.tenantId())
                || e.linkId() < 1
                || blank(e.domainNorm())
                || e.bucketVersion() != 1) result = "INVALID_SCHEMA";
        if (!EventIdentity.valid(e.eventId(), e.occurredAt())) result = "INVALID_EVENT_IDENTITY";
        String ua = e.userAgent() == null ? "" : e.userAgent().toLowerCase(Locale.ROOT);
        String browser =
                ua.contains("edg/")
                        ? "Edge"
                        : ua.contains("chrome/")
                                ? "Chrome"
                                : ua.contains("firefox/")
                                        ? "Firefox"
                                        : ua.contains("safari/") ? "Safari" : "Unknown";
        String os =
                ua.contains("android")
                        ? "Android"
                        : ua.contains("iphone") || ua.contains("ipad")
                                ? "iOS"
                                : ua.contains("windows")
                                        ? "Windows"
                                        : ua.contains("mac os")
                                                ? "macOS"
                                                : ua.contains("linux") ? "Linux" : "Unknown";
        String ip = normalizedIp(e.clientIp());
        var geo = "VALID".equals(result) ? geoLookup.lookup(e.clientIp())
                : GeoResult.unknown("NOT_APPLICABLE", geoLookup.version());
        return new EnrichedRecord(
                "CLICK",
                r.clusterId(),
                r.topicId(),
                r.topic(),
                r.partition(),
                r.offset(),
                r.receivedAt(),
                r.timestampType(),
                e.eventId(),
                sha256(EventJson.write(e)),
                e.tenantId(),
                e.linkId(),
                e.occurredAt(),
                blank(e.uvId()) ? "" : hash("uv", e.tenantId(), e.domainNorm(), e.uvId()),
                blank(ip) ? "" : hash("ip", e.tenantId(), ip),
                browser,
                os,
                ua.isBlank() ? "Unknown" : ua.contains("mobile") ? "Mobile" : "Desktop",
                geo.country(),
                refererDomain(e.referer()),
                "",
                "",
                302,
                "",
                TimeValidation.VERSION,
                result,
                DATASET,
                parserVersion(),
                HASH,
                geo.province(), geo.city(), geo.network(), geo.status(), geo.version());
    }

    private EnrichedRecord request(RawReceipt r, GatewayRequestEventV1 e) {
        String result = TimeValidation.validate(r, e.occurredAt(), futureTolerance);
        if (e.schemaVersion() != 1
                || blank(e.decisionId())
                || e.source() == null
                || e.stage() == null
                || e.status() < 100
                || e.status() > 599) result = "INVALID_SCHEMA";
        if (!EventIdentity.valid(e.decisionId(), e.occurredAt())) result = "INVALID_EVENT_IDENTITY";
        return new EnrichedRecord(
                "REQUEST",
                r.clusterId(),
                r.topicId(),
                r.topic(),
                r.partition(),
                r.offset(),
                r.receivedAt(),
                r.timestampType(),
                e.decisionId(),
                sha256(EventJson.write(e)),
                e.tenantId() == null ? "" : e.tenantId(),
                e.linkId() == null ? 0 : e.linkId(),
                e.occurredAt(),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                e.source() == null ? "" : e.source().name(),
                e.stage() == null ? "" : e.stage().name(),
                e.status(),
                e.reason(),
                TimeValidation.VERSION,
                result,
                DATASET,
                parserVersion(),
                HASH,
                "UNKNOWN", "UNKNOWN", "UNKNOWN", "NOT_APPLICABLE", geoLookup.version());
    }

    private EnrichedRecord rejected(RawReceipt r, String reason) {
        return new EnrichedRecord(
                "INVALID",
                r.clusterId(),
                r.topicId(),
                r.topic(),
                r.partition(),
                r.offset(),
                r.receivedAt(),
                r.timestampType(),
                r.identity(),
                sha256(r.payload()),
                "",
                0,
                Math.max(0, r.receivedAt()),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                0,
                reason,
                TimeValidation.VERSION,
                reason,
                DATASET,
                parserVersion(),
                HASH,
                "UNKNOWN", "UNKNOWN", "UNKNOWN", "NOT_APPLICABLE", geoLookup.version());
    }

    private String parserVersion() {
        return PARSER + "+" + (geoLookup.version().isEmpty() ? "geo-not-configured" : geoLookup.version());
    }

    private String hash(String... values) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (String v : values) {
                byte[] b = v.getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
                mac.update(b);
            }
            return HexFormat.of().formatHex(mac.doFinal(), 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String refererDomain(String s) {
        try {
            String host = URI.create(s).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalizedIp(String s) {
        if (s == null || !s.matches("[0-9a-fA-F:.]{2,45}")) return "";
        try {
            return InetAddress.getByName(s).getHostAddress();
        } catch (Exception e) {
            return "";
        }
    }
}
