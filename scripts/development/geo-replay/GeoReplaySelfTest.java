package com.jupiter.shortlink.tools;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.contract.geo.GeoLookup;
import com.jupiter.shortlink.contract.geo.GeoResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Local checks and rejecting CLI preflights: synthetic keys, no Kafka clients or event writes. */
public final class GeoReplaySelfTest {
    public static void main(String[] ignored) throws Exception {
        String key = "abcdefghijklmnopqrstuvwxyz0123456789";
        String fingerprint = EventEnricher.sha256(key);
        check(!GeoReplay.options(new String[] {"--bootstrap", "example:9092"}).containsKey("apply"));
        check(GeoReplay.options(new String[] {"--apply"}).containsKey("apply"));
        rejects(() -> GeoReplay.options(new String[] {"--apply", "--apply"}));
        var range = new SourceCut.Range("cluster", "topic", Topics.CLICK_RAW, 0, 10, 12);
        var cut = new GeoReplay.Cut(2, 1000, "fixed-db", new SourceCut(List.of(range)), fingerprint);
        GeoReplay.validate(cut, "fixed-db", fingerprint, 2);
        rejects(() -> GeoReplay.validate(cut, "other-db", fingerprint, 2));
        rejects(() -> GeoReplay.validate(cut, "fixed-db", fingerprint, 1));
        rejects(() -> GeoReplay.validate(cut, "fixed-db", EventEnricher.sha256("changed-key"), 2));
        rejects(() -> GeoReplay.validate(new GeoReplay.Cut(1, 1000, "fixed-db", cut.sourceCut(), null),
                "fixed-db", fingerprint, 2));
        rejects(() -> GeoReplay.validate(new GeoReplay.Cut(2, 1000, "fixed-db",
                new SourceCut(List.of(range, range)), fingerprint), "fixed-db", fingerprint, 4));
        rejects(() -> GeoReplay.validate(new GeoReplay.Cut(2, 1000, "fixed-db", new SourceCut(List.of(
                new SourceCut.Range("cluster", "topic", Topics.CLICK_ENRICHED, 0, 0, 1))), fingerprint),
                "fixed-db", fingerprint, 2));
        rejectingCliPreflight(cut, "another-012345678901234567890123456789", false);
        rejectingCliPreflight(cut, "another-012345678901234567890123456789", true);
        rejectingCliPreflight(new GeoReplay.Cut(1, 1000, "fixed-db", cut.sourceCut(), null), key, true);
        GeoLookup geo = new GeoLookup() {
            public GeoResult lookup(String ip) { return new GeoResult("CN", "广东省", "深圳市", "联通", "RESOLVED", version()); }
            public String version() { return "fixed-db"; }
        };
        try (var configured = new EventEnricher(key, 5000, geo); var legacy = new EventEnricher(key, 5000)) {
            var click = new ClickEventV1(EventIdentity.bind(1000, "click"), 1, 1000, "p", "t", 1,
                    "g", 1, "example.test", "x", 1, "cookie", "1.2.3.4", "Chrome/10", "", "r", "trace", 1);
            var raw = new RawReceipt("cluster", "topic", Topics.CLICK_RAW, 0, 10, 2000, "LogAppendTime", EventJson.write(click));
            var output = GeoReplay.enrich(raw, configured, legacy);
            var result = EventJson.read(output.json(), EnrichedRecord.class);
            check(output.topic().equals(Topics.CLICK_ENRICHED));
            check(output.receipt().equals(raw.identity()));
            check(result.payloadHash().equals(legacy.enrich(raw).payloadHash()));
            check(result.sourceOffset() == 10 && result.receivedAt() == 2000 && result.country().equals("CN"));
            check(result.geoVersion().equals("fixed-db") && result.province().equals("广东省"));
            check(GeoReplay.enrich(new RawReceipt("cluster", "topic", Topics.CLICK_RAW, 0, 10, 0,
                    "LogAppendTime", "invalid-json"), configured, legacy) == null);
            try (var wrongKey = new EventEnricher("another-012345678901234567890123456789", 5000, geo)) {
                rejects(() -> GeoReplay.enrich(raw, configured, wrongKey));
            }
        }
        System.out.println("GeoReplaySelfTest PASS: defaults, fixed bounds, source whitelist, geo/key pins, identity/hash preservation, invalid skip, hash mismatch; 3 real CLI preflights rejected before Kafka with zero writes");
    }

    private static void rejectingCliPreflight(GeoReplay.Cut capturedWithKeyA, String runtimeKey, boolean apply)
            throws Exception {
        Path folder = Files.createTempDirectory("shortlink-geo-key-preflight-");
        Path cut = folder.resolve("cut.json"), report = folder.resolve("report.json");
        Files.writeString(cut, EventJson.write(capturedWithKeyA), StandardCharsets.UTF_8);
        var command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), GeoReplay.class.getName(),
                "--bootstrap", "must-not-connect.invalid:1", "--cut", cut.toString(), "--report", report.toString()));
        if (apply) command.add("--apply");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(folder.resolve("cli.log").toFile());
        builder.environment().put("ANALYTICS_HASH_KEY", runtimeKey);
        builder.environment().keySet().removeIf(name -> name.startsWith("ANALYTICS_GEO_") || name.equals("KAFKA_SECURITY_PROPERTIES"));
        var process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Key preflight did not reject before client setup");
        }
        check(process.exitValue() == 1);
        Map<?, ?> result = EventJson.read(Files.readString(report, StandardCharsets.UTF_8), Map.class);
        check("FAILED".equals(result.get("status")));
        check("IllegalArgumentException".equals(result.get("errorClass")));
        check(Boolean.FALSE.equals(result.get("producerCreated")));
        for (String field : List.of("rawWrites", "archiveWrites", "consumerOffsetCommits", "producerAcknowledged"))
            check(((Number) result.get(field)).longValue() == 0);
        check(!result.containsKey("hashKeyFingerprintVerified") && !result.containsKey("geoVersion"));
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Self-test failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
}
