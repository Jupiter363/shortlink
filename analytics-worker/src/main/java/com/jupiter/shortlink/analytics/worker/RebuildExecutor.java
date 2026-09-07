package com.jupiter.shortlink.analytics.worker;

import com.jupiter.shortlink.contract.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public final class RebuildExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(RebuildExecutor.class);
    private final ControlLedger ledger;
    private final ObjectArchive archive;
    private final ClickHouseStore ch;
    private final WorkerSettings settings;
    private final EventEnricher enricher;
    private final String owner = UUID.randomUUID().toString();

    public RebuildExecutor(ControlLedger l, ObjectArchive a, ClickHouseStore ch, WorkerSettings s) {
        ledger = l;
        archive = a;
        this.ch = ch;
        settings = s;
        enricher = new EventEnricher(s.hashKey(), 5000);
    }

    @Scheduled(fixedDelayString = "${analytics.rebuild.poll-delay:2000}")
    public void tick() {
        Map<String, Object> job = null;
        try {
            job = ledger.claim(owner);
            if (job != null) run(job);
        } catch (Exception e) {
            LOG.warn("Rebuild failed: {}", e.getClass().getSimpleName() + ": " + e.getMessage());
            if (job != null)
                ledger.failed(job, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public void run(Map<String, Object> job) throws Exception {
        SourceCut cut = EventJson.read((String) job.get("source_cut"), SourceCut.class);
        long start = ((Number) job.get("start_ms")).longValue(),
                end = ((Number) job.get("end_ms")).longValue();
        if (System.currentTimeMillis() < end + 480000)
            throw new IllegalStateException("Online admission not closed");
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(10).toNanos();
        for (var range : cut.ranges()) {
            ledger.verifyArchiveCut(range);
            long after = -1;
            while (true) {
                var segments = ledger.segments(range, start, end, after);
                if (segments.isEmpty()) break;
                for (var segment : segments) {
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException("REBUILD_EXECUTION_BUDGET");
                    ledger.heartbeat(job);
                    readSegment(
                            job,
                            range,
                            (String) segment.get("object_key"),
                            (String) segment.get("checksum"),
                            start,
                            end);
                    after = ((Number) segment.get("start_offset")).longValue();
                }
            }
        }
        ledger.heartbeat(job);
        String build = ClickHouseStore.quote((String) job.get("build_id"));
        String facts =
                "SELECT kind,tenant_id,event_id,any(link_id) link_id,any(window_start)"
                    + " window_start,any(visitor_hash) visitor_hash,any(ip_hash)"
                    + " ip_hash,any(status) status,any(request_source)"
                    + " request_source,any(decision_stage) decision_stage FROM rebuild_input WHERE"
                    + " build_id="
                        + build
                        + " AND validation_result='VALID' GROUP BY kind,tenant_id,event_id HAVING"
                        + " uniqExact(payload_hash)=1";
        List<Map<String, Object>> output = new ArrayList<>();
        ch.query(
                "SELECT tenant_id,link_id,window_start,count()"
                        + " pv,uniqCombined64If(visitor_hash,visitor_hash!='')"
                        + " uv,uniqCombined64If(ip_hash,ip_hash!='') uip FROM ("
                        + facts
                        + ") WHERE kind='CLICK' GROUP BY tenant_id,link_id,window_start",
                r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tenant_id", r.get("tenant_id"));
                    row.put("link_id", r.get("link_id"));
                    row.put("window_start", r.get("window_start"));
                    row.put(
                            "window_end",
                            Long.parseLong(r.get("window_start").toString()) + 300000);
                    row.put("build_id", job.get("build_id"));
                    row.put("recovery_epoch", job.get("recovery_epoch"));
                    row.put("revision", 1);
                    row.put("pv", r.get("pv"));
                    row.put("uv", r.get("uv"));
                    row.put("uip", r.get("uip"));
                    row.put("visitor_sketch", "");
                    row.put("ip_sketch", "");
                    row.put("metric_version", "click-v1");
                    row.put("dataset_version", EventEnricher.DATASET);
                    output.add(row);
                    if (output.size() >= 500) {
                        ch.insert("window_results", output);
                        output.clear();
                        ledger.heartbeat(job);
                    }
                });
        ch.insert("window_results", output);
        // Query exact deduplicated receipt content on each configured replica, not merely INSERT
        // ACK.
        String fingerprint =
                "SELECT count()"
                    + " n,toString(groupBitXor(cityHash64(receipt_id,payload_hash,validation_result)))"
                    + " digest FROM (SELECT receipt_id,payload_hash,validation_result FROM"
                    + " rebuild_input WHERE build_id="
                        + build
                        + " GROUP BY receipt_id,payload_hash,validation_result)";
        String expected = null;
        for (String replica : settings.replicas()) {
            List<String> values = new ArrayList<>();
            ch.query(replica, fingerprint, r -> values.add(EventJson.write(r)));
            String found = String.join("", values);
            if (expected == null) expected = found;
            else if (!expected.equals(found)) throw new IllegalStateException("REPLICA_NOT_READY");
        }
        job.put("coverage_proof", expected);
        ledger.heartbeat(job);
        ledger.publish(job, settings.replicas());
    }

    private void readSegment(
            Map<String, Object> job,
            SourceCut.Range range,
            String key,
            String expected,
            long start,
            long end)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var in = new DigestInputStream(archive.open(key), digest);
                var reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = BoundedLines.read(reader, 524288)) != null; ) {
                ArchivedEvent archived = EventJson.read(line, ArchivedEvent.class);
                RawReceipt raw = archived.raw();
                if (!range.contains(raw)) continue;
                EnrichedRecord e = archived.verified();
                if (!archived.hashKeyFingerprint().equals(EventEnricher.sha256(settings.hashKey())))
                    throw new IllegalStateException(
                            "HASH_KEY_VERSION_CHANGED_REQUIRES_NEW_DATASET");
                if (e.occurredAt() < start || e.occurredAt() >= end) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("build_id", job.get("build_id"));
                row.put("window_start", Math.floorDiv(e.occurredAt(), 300000) * 300000);
                row.put("kind", e.kind());
                row.put("tenant_id", e.tenantId());
                row.put("link_id", e.linkId());
                row.put("event_id", e.eventId());
                row.put("payload_hash", e.payloadHash());
                row.put("occurred_at", e.occurredAt());
                row.put("received_at", e.receivedAt());
                row.put("visitor_hash", e.visitorHash());
                row.put("ip_hash", e.ipHash());
                row.put("browser", e.browser());
                row.put("os", e.os());
                row.put("device", e.device());
                row.put("country", e.country());
                row.put("referer_domain", e.refererDomain());
                row.put("request_source", e.requestSource());
                row.put("decision_stage", e.decisionStage());
                row.put("status", e.status());
                row.put("validation_result", e.validationResult());
                row.put("receipt_id", raw.identity());
                rows.add(row);
                if (rows.size() >= 500) {
                    ch.insert("rebuild_input", rows);
                    rows.clear();
                    ledger.heartbeat(job);
                }
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected))
            throw new IllegalStateException("ARCHIVE_CHECKSUM_MISMATCH");
        ch.insert("rebuild_input", rows);
    }
}
