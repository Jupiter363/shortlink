package com.jupiter.shortlink.analytics.worker;

import com.jupiter.shortlink.contract.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Durable bounded catalog scan and raw verification. An HTTP retry never repeats an unbounded
 * in-memory scan.
 */
@Component
public class ArchiveRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(ArchiveRecovery.class);
    private final ObjectArchive objects;
    private final ControlLedger ledger;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final String owner = UUID.randomUUID().toString();

    public ArchiveRecovery(
            ObjectArchive o, ControlLedger l, JdbcTemplate d, PlatformTransactionManager manager) {
        objects = o;
        ledger = l;
        db = d;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    public Map<String, Object> reconcile(String epoch) {
        ledger.assertEpoch(epoch);
        db.update(
                "INSERT INTO analytics_recovery_run(recovery_epoch,status,phase)"
                    + " VALUES(?,'SCANNING','SCANNING') ON DUPLICATE KEY UPDATE failures=CASE WHEN"
                    + " status='FAILED' THEN 0 ELSE failures END,status=CASE WHEN status='FAILED'"
                    + " THEN phase ELSE status END",
                epoch);
        return status(epoch);
    }

    public Map<String, Object> status(String epoch) {
        ledger.assertEpoch(epoch);
        var rows =
                db.queryForList(
                        "SELECT"
                            + " recovery_epoch,status,phase,scan_cursor,verified_segments,failures,error_code"
                            + " FROM analytics_recovery_run WHERE recovery_epoch=?",
                        epoch);
        if (rows.isEmpty())
            throw new IllegalStateException("Recovery reconciliation has not been requested");
        return rows.get(0);
    }

    public void requireVerified(String epoch) {
        if (!"VERIFIED".equals(status(epoch).get("status")))
            throw new IllegalStateException("Recovery catalog/raw verification is incomplete");
    }

    @Scheduled(fixedDelayString = "${analytics.recovery.poll-delay:500}")
    public void tick() {
        Map<String, Object> run = null;
        try {
            run = claim();
            if (run == null) return;
            if ("SCANNING".equals(run.get("status"))) scan(run);
            else if ("PUBLICATIONS".equals(run.get("status"))) publications(run);
            else verify(run);
        } catch (Exception e) {
            LOG.warn("Recovery task did not advance: {}", e.getClass().getSimpleName());
            if (run != null) fail(run, e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> claim() {
        return tx.execute(
                s -> {
                    String epoch = ledger.epoch().epoch();
                    lockEpoch(epoch);
                    var rows =
                            db.queryForList(
                                    "SELECT * FROM analytics_recovery_run WHERE recovery_epoch=?"
                                        + " AND status IN ('SCANNING','PUBLICATIONS','VERIFYING')"
                                        + " AND (lease_until IS NULL OR"
                                        + " lease_until<CURRENT_TIMESTAMP(3) OR lease_owner=?) FOR"
                                        + " UPDATE",
                                    epoch,
                                    owner);
                    if (rows.isEmpty()) return null;
                    var row = rows.get(0);
                    long token = ((Number) row.get("lease_token")).longValue() + 1;
                    db.update(
                            "UPDATE analytics_recovery_run SET"
                                    + " lease_owner=?,lease_token=?,lease_until=? WHERE"
                                    + " recovery_epoch=?",
                            owner,
                            token,
                            until(),
                            epoch);
                    row.put("lease_token", token);
                    row.put("lease_owner", owner);
                    return row;
                });
    }

    private void scan(Map<String, Object> run) throws Exception {
        String epoch = run.get("recovery_epoch").toString();
        var keys = objects.page("catalog/segments/", (String) run.get("scan_cursor"), 200);
        for (String key : keys) {
            heartbeat(run);
            ArchiveSegment segment;
            try (var in = objects.open(key)) {
                segment =
                        EventJson.read(
                                new String(in.readNBytes(65536), StandardCharsets.UTF_8),
                                ArchiveSegment.class);
            }
            if (segment.start() < 0 || segment.end() < segment.start() || segment.partition() < 0)
                throw new IllegalStateException("Invalid archive catalog range");
            db.update(
                    "INSERT IGNORE INTO"
                        + " analytics_recovery_catalog(recovery_epoch,segment_id,cluster_id,topic_id,partition_id,start_offset,end_offset,segment_payload)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    epoch,
                    EventEnricher.sha256(key),
                    segment.clusterId(),
                    segment.topicId(),
                    segment.partition(),
                    segment.start(),
                    segment.end(),
                    EventJson.write(segment));
        }
        tx.executeWithoutResult(
                s -> {
                    guard(run);
                    if (keys.isEmpty())
                        db.update(
                                "UPDATE analytics_recovery_run SET"
                                    + " status='PUBLICATIONS',phase='PUBLICATIONS',scan_cursor=NULL,lease_until=NULL"
                                    + " WHERE recovery_epoch=?",
                                epoch);
                    else
                        db.update(
                                "UPDATE analytics_recovery_run SET"
                                        + " scan_cursor=?,lease_until=NULL,failures=0 WHERE"
                                        + " recovery_epoch=?",
                                keys.get(keys.size() - 1),
                                epoch);
                });
    }

    private void publications(Map<String, Object> run) throws Exception {
        String epoch = run.get("recovery_epoch").toString();
        var keys = objects.page("catalog/publications/", (String) run.get("scan_cursor"), 200);
        Set<Long> windows = new HashSet<>();
        for (String key : keys) {
            heartbeat(run);
            try (var in = objects.open(key)) {
                byte[] bytes = in.readNBytes(1048577);
                if (bytes.length > 1048576)
                    throw new IllegalStateException("PUBLICATION_CATALOG_BUDGET");
                var value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
                if (!value.has("windowStart") || !value.get("windowStart").canConvertToLong())
                    throw new IllegalStateException("INVALID_PUBLICATION_CATALOG");
                long window = value.get("windowStart").asLong();
                if (window < 0 || window % 300000 != 0)
                    throw new IllegalStateException("INVALID_PUBLICATION_CATALOG");
                windows.add(window);
            }
        }
        tx.executeWithoutResult(
                s -> {
                    guard(run);
                    for (long window : windows) ledger.requestRepair(window);
                    if (keys.isEmpty())
                        db.update(
                                "UPDATE analytics_recovery_run SET"
                                    + " status='VERIFYING',phase='VERIFYING',scan_cursor=NULL,lease_until=NULL"
                                    + " WHERE recovery_epoch=?",
                                epoch);
                    else
                        db.update(
                                "UPDATE analytics_recovery_run SET"
                                        + " scan_cursor=?,lease_until=NULL,failures=0 WHERE"
                                        + " recovery_epoch=?",
                                keys.get(keys.size() - 1),
                                epoch);
                });
    }

    private void verify(Map<String, Object> run) throws Exception {
        String epoch = run.get("recovery_epoch").toString();
        var pending =
                db.queryForList(
                        "SELECT segment_id,segment_payload FROM analytics_recovery_catalog WHERE"
                            + " recovery_epoch=? AND verified=FALSE ORDER BY"
                            + " cluster_id,topic_id,partition_id,start_offset,end_offset LIMIT 20",
                        epoch);
        for (var item : pending) {
            heartbeat(run);
            var segment =
                    EventJson.read(item.get("segment_payload").toString(), ArchiveSegment.class);
            verifySegment(epoch, segment, run);
            tx.executeWithoutResult(
                    s -> {
                        guard(run);
                        db.update(
                                "UPDATE analytics_recovery_catalog SET verified=TRUE WHERE"
                                        + " recovery_epoch=? AND segment_id=?",
                                epoch,
                                item.get("segment_id"));
                        db.update(
                                "UPDATE analytics_recovery_run SET"
                                        + " verified_segments=verified_segments+1,failures=0 WHERE"
                                        + " recovery_epoch=?",
                                epoch);
                    });
        }
        tx.executeWithoutResult(
                s -> {
                    guard(run);
                    db.update(
                            "UPDATE analytics_recovery_run SET status=?,phase=?,lease_until=NULL"
                                    + " WHERE recovery_epoch=?",
                            pending.isEmpty() ? "VERIFIED" : "VERIFYING",
                            pending.isEmpty() ? "VERIFIED" : "VERIFYING",
                            epoch);
                });
    }

    private void verifySegment(String epoch, ArchiveSegment segment, Map<String, Object> run)
            throws Exception {
        long from =
                Math.max(
                        segment.start(),
                        ledger.nextOffset(
                                segment.clusterId(),
                                segment.topicId(),
                                segment.topic(),
                                segment.partition(),
                                segment.start(),
                                epoch));
        int count = 0;
        Set<Long> windows = new HashSet<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = new DigestInputStream(objects.open(segment.objectKey()), digest);
                var reader =
                        new java.io.BufferedReader(
                                new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            int lines = 0;
            for (String line; (line = BoundedLines.read(reader, 524288)) != null; ) {
                if (++lines % 1000 == 0) heartbeat(run);
                var archived = EventJson.read(line, ArchivedEvent.class);
                var raw = archived.raw();
                if (raw.offset() < segment.start() || raw.offset() >= segment.end()) continue;
                if (!raw.clusterId().equals(segment.clusterId())
                        || !raw.topicId().equals(segment.topicId())
                        || raw.partition() != segment.partition())
                    throw new IllegalStateException("ARCHIVE_SOURCE_MISMATCH");
                if (raw.offset() >= from) count++;
                var event = archived.verified();
                if (event.valid() && (event.click() || event.linkId() > 0))
                    windows.add(Math.floorDiv(event.occurredAt(), 300000) * 300000);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(segment.checksum()))
            throw new IllegalStateException("ARCHIVE_CHECKSUM_MISMATCH");
        if (from < segment.end())
            ledger.archived(
                    epoch,
                    segment.clusterId(),
                    segment.topicId(),
                    segment.topic(),
                    segment.partition(),
                    from,
                    segment.end(),
                    segment.objectKey(),
                    segment.checksum(),
                    count,
                    segment.minReceivedAt(),
                    segment.maxReceivedAt());
        tx.executeWithoutResult(
                s -> {
                    guard(run);
                    ledger.indexArchivedObject(epoch, segment.objectKey(), windows);
                });
    }

    private void heartbeat(Map<String, Object> run) {
        ledger.assertEpoch(run.get("recovery_epoch").toString());
        if (db.update(
                        "UPDATE analytics_recovery_run SET lease_until=? WHERE recovery_epoch=? AND"
                                + " lease_token=? AND lease_owner=? AND"
                                + " lease_until>=CURRENT_TIMESTAMP(3) AND status IN"
                                + " ('SCANNING','PUBLICATIONS','VERIFYING')",
                        until(),
                        run.get("recovery_epoch"),
                        run.get("lease_token"),
                        owner)
                != 1) throw new IllegalStateException("RECOVERY_LEASE_LOST");
    }

    private void guard(Map<String, Object> run) {
        lockEpoch(run.get("recovery_epoch").toString());
        var rows =
                db.queryForList(
                        "SELECT status FROM analytics_recovery_run WHERE recovery_epoch=? AND"
                                + " lease_token=? AND lease_owner=? AND"
                                + " lease_until>=CURRENT_TIMESTAMP(3) FOR UPDATE",
                        run.get("recovery_epoch"),
                        run.get("lease_token"),
                        owner);
        if (rows.isEmpty()) throw new IllegalStateException("RECOVERY_LEASE_LOST");
    }

    private void lockEpoch(String epoch) {
        ledger.assertEpoch(epoch);
        String current =
                db.queryForObject(
                        "SELECT recovery_epoch FROM analytics_epoch WHERE singleton=1 FOR UPDATE",
                        String.class);
        if (!epoch.equals(current)) throw new IllegalStateException("STALE_RECOVERY_EPOCH");
    }

    private Timestamp until() {
        return Timestamp.from(Instant.now().plusSeconds(120));
    }

    private void fail(Map<String, Object> run, String error) {
        db.update(
                "UPDATE analytics_recovery_run SET failures=failures+1,status=CASE WHEN failures>=4"
                        + " THEN 'FAILED' ELSE status END,error_code=?,lease_until=NULL WHERE"
                        + " recovery_epoch=? AND lease_token=? AND lease_owner=?",
                error,
                run.get("recovery_epoch"),
                run.get("lease_token"),
                owner);
    }
}
