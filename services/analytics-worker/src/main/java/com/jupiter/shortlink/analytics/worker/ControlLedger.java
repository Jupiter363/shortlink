package com.jupiter.shortlink.analytics.worker;

import com.jupiter.shortlink.contract.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class ControlLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectArchive archive;

    public ControlLedger(
            JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectArchive archive) {
        this.jdbc = jdbc;
        this.archive = archive;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    public record Epoch(String epoch, String mode, boolean commandAck, long gateFence) {}

    public Epoch epoch() {
        var list =
                jdbc.query(
                        "SELECT recovery_epoch,mode,command_ack,gate_fence FROM analytics_epoch"
                                + " WHERE singleton=1",
                        (r, n) ->
                                new Epoch(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getBoolean(3),
                                        r.getLong(4)));
        if (list.isEmpty()) throw new IllegalStateException("Analytics epoch is not initialized");
        if (!list.get(0).epoch.equals(archive.activeEpoch()))
            throw new IllegalStateException("RECOVERY_EPOCH_MISMATCH");
        return list.get(0);
    }

    public void assertEpoch(String epoch) {
        if (!epoch().epoch.equals(epoch)) throw new IllegalStateException("STALE_RECOVERY_EPOCH");
    }

    public String beginRecovery() {
        return beginRecovery(0);
    }

    public String beginRecovery(long gateFence) {
        String next = UUID.randomUUID().toString();
        archive.establishEpoch(next);
        tx.executeWithoutResult(
                s -> {
                    jdbc.update(
                            "INSERT INTO"
                                + " analytics_epoch(singleton,recovery_epoch,mode,command_ack,gate_fence)"
                                + " VALUES(1,?,'RECOVERING',FALSE,?) ON DUPLICATE KEY UPDATE"
                                + " recovery_epoch=VALUES(recovery_epoch),mode='RECOVERING',command_ack=FALSE,gate_fence=VALUES(gate_fence)",
                            next,
                            gateFence);
                    jdbc.update(
                            "UPDATE analytics_rebuild_job SET"
                                    + " status='STALE',lease_token=lease_token+1 WHERE status IN"
                                    + " ('PENDING','RUNNING')");
                });
        return next;
    }

    public void activate(String epoch) {
        tx.executeWithoutResult(
                s -> {
                    lockEpoch(epoch);
                    if (jdbc.update(
                                    "UPDATE analytics_epoch SET mode='ACTIVE',command_ack=TRUE"
                                            + " WHERE singleton=1 AND recovery_epoch=?",
                                    epoch)
                            != 1) throw new IllegalStateException("STALE_RECOVERY_EPOCH");
                });
    }

    private void lockEpoch(String epoch) {
        if (!epoch.equals(archive.activeEpoch()))
            throw new IllegalStateException("STALE_RECOVERY_EPOCH");
        String actual =
                jdbc.queryForObject(
                        "SELECT recovery_epoch FROM analytics_epoch WHERE singleton=1 FOR UPDATE",
                        String.class);
        if (!Objects.equals(actual, epoch)) throw new IllegalStateException("STALE_RECOVERY_EPOCH");
    }

    public long nextOffset(
            String cluster,
            String topicId,
            String topic,
            int partition,
            long earliest,
            String epoch) {
        assertEpoch(epoch);
        jdbc.update(
                "INSERT IGNORE INTO"
                    + " analytics_archive_progress(cluster_id,topic_id,topic,partition_id,next_offset,recovery_epoch)"
                    + " VALUES(?,?,?,?,?,?)",
                cluster,
                topicId,
                topic,
                partition,
                earliest,
                epoch);
        return jdbc.queryForObject(
                "SELECT next_offset FROM analytics_archive_progress WHERE cluster_id=? AND"
                        + " topic_id=? AND partition_id=?",
                Long.class,
                cluster,
                topicId,
                partition);
    }

    public void archived(
            String epoch,
            String cluster,
            String topicId,
            String topic,
            int partition,
            long start,
            long end,
            String key,
            String digest,
            int count,
            long min,
            long max) {
        archived(
                epoch, cluster, topicId, topic, partition, start, end, key, digest, count, min, max,
                Set.of());
    }

    public void archived(
            String epoch,
            String cluster,
            String topicId,
            String topic,
            int partition,
            long start,
            long end,
            String key,
            String digest,
            int count,
            long min,
            long max,
            Set<Long> windows) {
        tx.executeWithoutResult(
                s -> {
                    lockEpoch(epoch);
                    Long old =
                            jdbc.queryForObject(
                                    "SELECT next_offset FROM analytics_archive_progress WHERE"
                                            + " cluster_id=? AND topic_id=? AND partition_id=? FOR"
                                            + " UPDATE",
                                    Long.class,
                                    cluster,
                                    topicId,
                                    partition);
                    String id =
                            EventEnricher.sha256(
                                    cluster + "/" + topicId + "/" + partition + "/" + start + "/"
                                            + end);
                    var existing =
                            jdbc.queryForList(
                                    "SELECT checksum FROM analytics_archive_segment WHERE"
                                            + " segment_id=?",
                                    id);
                    if (!existing.isEmpty() && !digest.equals(existing.get(0).get("checksum")))
                        throw new IllegalStateException("ARCHIVE_CONTENT_CONFLICT");
                    jdbc.update(
                            "INSERT IGNORE INTO"
                                + " analytics_archive_segment(segment_id,cluster_id,topic_id,topic,partition_id,start_offset,end_offset,object_key,checksum,record_count,min_received_at,max_received_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            cluster,
                            topicId,
                            topic,
                            partition,
                            start,
                            end,
                            key,
                            digest,
                            count,
                            min,
                            max);
                    if (old >= start && old < end)
                        jdbc.update(
                                "UPDATE analytics_archive_progress SET"
                                        + " next_offset=?,recovery_epoch=? WHERE cluster_id=? AND"
                                        + " topic_id=? AND partition_id=?",
                                end,
                                epoch,
                                cluster,
                                topicId,
                                partition);
                    else if (old < end) throw new IllegalStateException("ARCHIVE_COVERAGE_GAP");
                    for (long window : windows) {
                        jdbc.update(
                                "INSERT IGNORE INTO"
                                        + " analytics_archive_window(window_start,segment_id)"
                                        + " VALUES(?,?)",
                                window,
                                id);
                        requestRepair(window);
                    }
                });
    }

    public void indexArchivedObject(String epoch, String key, Set<Long> windows) {
        tx.executeWithoutResult(
                s -> {
                    lockEpoch(epoch);
                    var segments =
                            jdbc.queryForList(
                                    "SELECT segment_id FROM analytics_archive_segment WHERE"
                                            + " object_key=?",
                                    key);
                    for (var segment : segments)
                        for (long window : windows)
                            jdbc.update(
                                    "INSERT IGNORE INTO"
                                            + " analytics_archive_window(window_start,segment_id)"
                                            + " VALUES(?,?)",
                                    window,
                                    segment.get("segment_id"));
                    for (long window : windows) requestRepair(window);
                });
    }

    public SourceCut cut() {
        return new SourceCut(
                jdbc.query(
                        "SELECT p.cluster_id,p.topic_id,p.topic,p.partition_id,COALESCE((SELECT"
                            + " MIN(s.start_offset) FROM analytics_archive_segment s WHERE"
                            + " s.cluster_id=p.cluster_id AND s.topic_id=p.topic_id AND"
                            + " s.partition_id=p.partition_id),p.next_offset),p.next_offset FROM"
                            + " analytics_archive_progress p",
                        (r, n) ->
                                new SourceCut.Range(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getInt(4),
                                        r.getLong(5),
                                        r.getLong(6))));
    }

    public void observed(
            String epoch, String cluster, String topic, int partition, long offset, long observed) {
        assertEpoch(epoch);
        jdbc.update(
                "UPDATE analytics_archive_progress SET observed_at=? WHERE cluster_id=? AND"
                        + " topic_id=? AND partition_id=? AND next_offset=?",
                observed,
                cluster,
                topic,
                partition,
                offset);
    }

    public Map<String, Object> coverage(long start) {
        var e = epoch();
        if (!e.mode.equals("ACTIVE") || !e.commandAck)
            throw new IllegalStateException("RECOVERY_GATE_CLOSED");
        var ranges =
                jdbc.query(
                        "SELECT p.cluster_id,p.topic_id,p.topic,p.partition_id,COALESCE((SELECT"
                                + " MIN(s.start_offset) FROM analytics_archive_segment s WHERE"
                                + " s.cluster_id=p.cluster_id AND s.topic_id=p.topic_id AND"
                                + " s.partition_id=p.partition_id AND s.max_received_at>=? AND"
                                + " s.end_offset<=p.next_offset),p.next_offset),p.next_offset FROM"
                                + " analytics_archive_progress p",
                        (r, n) ->
                                new SourceCut.Range(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getInt(4),
                                        r.getLong(5),
                                        r.getLong(6)),
                        Math.max(0, start - 5000));
        List<Map<String, Object>> counts = new ArrayList<>();
        for (var r : ranges) {
            var summary =
                    jdbc.queryForMap(
                            "SELECT COALESCE(SUM(record_count),0) records,COALESCE(SUM(CASE WHEN"
                                + " previous_end IS NOT NULL AND previous_end<>start_offset THEN 1"
                                + " ELSE 0 END),0) gaps,COALESCE(MIN(start_offset),?)"
                                + " first_offset,COALESCE(MAX(end_offset),?) last_offset FROM"
                                + " (SELECT start_offset,end_offset,record_count,LAG(end_offset)"
                                + " OVER(ORDER BY start_offset) previous_end FROM"
                                + " analytics_archive_segment WHERE cluster_id=? AND topic_id=? AND"
                                + " partition_id=? AND end_offset>? AND start_offset<?)"
                                + " ordered_segments",
                            r.start(),
                            r.end(),
                            r.clusterId(),
                            r.topicId(),
                            r.partition(),
                            r.start(),
                            r.end());
            if (((Number) summary.get("gaps")).longValue() != 0
                    || ((Number) summary.get("first_offset")).longValue() != r.start()
                    || ((Number) summary.get("last_offset")).longValue() != r.end())
                throw new IllegalStateException("COVERAGE_INTERVAL_REQUIRES_RECONCILIATION");
            long count = ((Number) summary.get("records")).longValue();
            counts.add(
                    Map.of(
                            "clusterId",
                            r.clusterId(),
                            "topicId",
                            r.topicId(),
                            "partition",
                            r.partition(),
                            "count",
                            count));
        }
        Long observed =
                jdbc.queryForObject(
                        "SELECT MIN(observed_at) FROM analytics_archive_progress", Long.class);
        return Map.of(
                "sourceCut",
                new SourceCut(ranges),
                "receipts",
                counts,
                "observedAt",
                observed == null ? 0 : observed,
                "recoveryEpoch",
                e.epoch);
    }

    public List<Map<String, Object>> segments(SourceCut.Range range) {
        return jdbc.queryForList(
                "SELECT * FROM analytics_archive_segment WHERE cluster_id=? AND topic_id=? AND"
                    + " partition_id=? AND end_offset>? AND start_offset<? ORDER BY start_offset",
                range.clusterId(),
                range.topicId(),
                range.partition(),
                range.start(),
                range.end());
    }

    public void verifyArchiveCut(SourceCut.Range range) {
        if (range.start() == range.end()) return;
        var summary =
                jdbc.queryForMap(
                        "SELECT COUNT(*) segment_count,COALESCE(SUM(CASE WHEN previous_end IS NOT NULL AND"
                            + " previous_end<>start_offset THEN 1 ELSE 0 END),0)"
                            + " gaps,COALESCE(MIN(start_offset),?)"
                            + " first_offset,COALESCE(MAX(end_offset),?) last_offset FROM (SELECT"
                            + " start_offset,end_offset,LAG(end_offset) OVER(ORDER BY start_offset)"
                            + " previous_end FROM analytics_archive_segment WHERE cluster_id=? AND"
                            + " topic_id=? AND partition_id=? AND end_offset>? AND start_offset<?)"
                            + " ordered_segments",
                        range.start(),
                        range.end(),
                        range.clusterId(),
                        range.topicId(),
                        range.partition(),
                        range.start(),
                        range.end());
        if (((Number) summary.get("segment_count")).longValue() == 0
                || ((Number) summary.get("gaps")).longValue() != 0
                || ((Number) summary.get("first_offset")).longValue() > range.start()
                || ((Number) summary.get("last_offset")).longValue() < range.end())
            throw new IllegalStateException("ARCHIVE_COVERAGE_GAP");
    }

    public List<Map<String, Object>> segments(
            SourceCut.Range range, long start, long end, long after) {
        return jdbc.queryForList(
                "SELECT s.* FROM analytics_archive_segment s WHERE cluster_id=? AND topic_id=? AND"
                    + " partition_id=? AND end_offset>? AND start_offset<? AND start_offset>? AND"
                    + " EXISTS(SELECT 1 FROM analytics_archive_window w WHERE"
                    + " w.segment_id=s.segment_id AND w.window_start>=? AND w.window_start<?) ORDER"
                    + " BY start_offset LIMIT 200",
                range.clusterId(),
                range.topicId(),
                range.partition(),
                range.start(),
                range.end(),
                after,
                start,
                end);
    }

    public String enqueue(long start, long end, SourceCut cut) {
        if (start < 0
                || end <= start
                || end - start > 86_400_000L
                || start % 300000 != 0
                || end % 300000 != 0)
            throw new IllegalArgumentException(
                    "Rebuild must cover aligned windows, maximum one day");
        if (cut.ranges().isEmpty())
            throw new IllegalArgumentException("No proven archive coverage");
        String id = UUID.randomUUID().toString();
        Long observed =
                jdbc.queryForObject(
                        "SELECT MIN(observed_at) FROM analytics_archive_progress", Long.class);
        jdbc.update(
                "INSERT INTO"
                    + " analytics_rebuild_job(job_id,recovery_epoch,source_cut,source_observed_at,start_ms,end_ms,status,build_id)"
                    + " VALUES(?,?,?,?,?,?,'PENDING',?)",
                id,
                epoch().epoch,
                EventJson.write(cut),
                observed == null ? 0 : observed,
                start,
                end,
                id);
        return id;
    }

    public void requestRepair(long window) {
        jdbc.update(
                "INSERT INTO analytics_repair_request(window_start,generation) VALUES(?,1) ON"
                        + " DUPLICATE KEY UPDATE generation=generation+1",
                window);
    }

    public void requestRepair(EnrichedRecord event) {
        tx.executeWithoutResult(
                s -> {
                    long w = Math.floorDiv(event.occurredAt(), 300000) * 300000;
                    requestRepair(w);
                    var rows =
                            jdbc.queryForList(
                                    "SELECT required_cut FROM analytics_repair_request WHERE"
                                            + " window_start=? FOR UPDATE",
                                    w);
                    List<SourceCut.Range> ranges = new ArrayList<>();
                    Object old = rows.get(0).get("required_cut");
                    if (old != null)
                        ranges.addAll(EventJson.read(old.toString(), SourceCut.class).ranges());
                    var needed =
                            new SourceCut.Range(
                                    event.clusterId(),
                                    event.topicId(),
                                    event.sourceTopic(),
                                    event.sourcePartition(),
                                    event.sourceOffset(),
                                    event.sourceOffset() + 1);
                    for (int i = 0; i < ranges.size(); i++) {
                        var r = ranges.get(i);
                        if (r.clusterId().equals(needed.clusterId())
                                && r.topicId().equals(needed.topicId())
                                && r.partition() == needed.partition()) {
                            needed =
                                    new SourceCut.Range(
                                            r.clusterId(),
                                            r.topicId(),
                                            r.topic(),
                                            r.partition(),
                                            Math.min(r.start(), needed.start()),
                                            Math.max(r.end(), needed.end()));
                            ranges.remove(i);
                            break;
                        }
                    }
                    ranges.add(needed);
                    jdbc.update(
                            "UPDATE analytics_repair_request SET required_cut=? WHERE"
                                    + " window_start=?",
                            EventJson.write(new SourceCut(ranges)),
                            w);
                });
    }

    public void planRequestedRepair() {
        tx.executeWithoutResult(
                s -> {
                    var e = epoch();
                    lockEpoch(e.epoch);
                    var pending =
                            jdbc.queryForList(
                                    "SELECT window_start,generation,required_cut FROM"
                                        + " analytics_repair_request ORDER BY window_start LIMIT 1"
                                        + " FOR UPDATE");
                    if (pending.isEmpty()) return;
                    long w = ((Number) pending.get(0).get("window_start")).longValue();
                    if (System.currentTimeMillis() < w + 780000) return;
                    Long running =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM analytics_rebuild_job WHERE"
                                            + " recovery_epoch=? AND start_ms=? AND status IN"
                                            + " ('PENDING','RUNNING')",
                                    Long.class,
                                    e.epoch,
                                    w);
                    if (running != 0) return;
                    SourceCut available = cut();
                    Object required = pending.get(0).get("required_cut");
                    if (required != null
                            && !available.covers(
                                    EventJson.read(required.toString(), SourceCut.class))) return;
                    enqueue(w, w + 300000, available);
                    jdbc.update(
                            "DELETE FROM analytics_repair_request WHERE window_start=? AND"
                                    + " generation=?",
                            w,
                            pending.get(0).get("generation"));
                });
    }

    public void requestNewestClosedWindow() {
        tx.executeWithoutResult(
                s -> {
                    var e = epoch();
                    lockEpoch(e.epoch);
                    long end = Math.floorDiv(System.currentTimeMillis() - 480000, 300000) * 300000;
                    Long earliest =
                            jdbc.queryForObject(
                                    "SELECT MIN(min_received_at) FROM analytics_archive_segment"
                                            + " WHERE min_received_at>0",
                                    Long.class);
                    long first =
                            earliest == null
                                    ? end - 300000
                                    : Math.floorDiv(earliest, 300000) * 300000;
                    jdbc.update(
                            "INSERT IGNORE INTO"
                                + " analytics_window_schedule(singleton,next_window,recovery_epoch)"
                                + " VALUES(1,?,?)",
                            first,
                            e.epoch);
                    var row =
                            jdbc.queryForMap(
                                    "SELECT next_window,recovery_epoch FROM"
                                            + " analytics_window_schedule WHERE singleton=1 FOR"
                                            + " UPDATE");
                    long next = ((Number) row.get("next_window")).longValue();
                    if (!e.epoch.equals(row.get("recovery_epoch"))) next = first;
                    int budget = 100;
                    while (next < end && budget-- > 0) {
                        requestRepair(next);
                        next += 300000;
                    }
                    jdbc.update(
                            "UPDATE analytics_window_schedule SET next_window=?,recovery_epoch=?"
                                    + " WHERE singleton=1",
                            next,
                            e.epoch);
                    Long observed =
                            jdbc.queryForObject(
                                    "SELECT MIN(observed_at) FROM analytics_archive_progress",
                                    Long.class);
                    if (observed != null)
                        for (var m :
                                jdbc.queryForList(
                                        "SELECT window_start FROM analytics_manifest WHERE"
                                                + " recovery_epoch=? AND finalized=FALSE AND"
                                                + " window_start+86705000<=? ORDER BY window_start"
                                                + " LIMIT 100",
                                        e.epoch,
                                        observed))
                            requestRepair(((Number) m.get("window_start")).longValue());
                });
    }

    public Map<String, Object> claim(String owner) {
        return tx.execute(
                s -> {
                    Epoch e = epoch();
                    lockEpoch(e.epoch);
                    var rows =
                            jdbc.queryForList(
                                    "SELECT * FROM analytics_rebuild_job WHERE recovery_epoch=? AND"
                                        + " (status='PENDING' OR (status='RUNNING' AND"
                                        + " lease_until<CURRENT_TIMESTAMP(3))) AND attempts<5 ORDER"
                                        + " BY created_at LIMIT 1 FOR UPDATE",
                                    e.epoch);
                    if (rows.isEmpty()) return null;
                    var job = rows.get(0);
                    long token = ((Number) job.get("lease_token")).longValue() + 1;
                    jdbc.update(
                            "UPDATE analytics_rebuild_job SET"
                                + " status='RUNNING',lease_owner=?,lease_token=?,lease_until=?,attempts=attempts+1"
                                + " WHERE job_id=?",
                            owner,
                            token,
                            Timestamp.from(Instant.now().plusSeconds(120)),
                            job.get("job_id"));
                    job.put("lease_token", token);
                    job.put("lease_owner", owner);
                    return job;
                });
    }

    public void heartbeat(Map<String, Object> job) {
        assertEpoch((String) job.get("recovery_epoch"));
        int changed =
                jdbc.update(
                        "UPDATE analytics_rebuild_job SET lease_until=? WHERE job_id=? AND"
                                + " lease_token=? AND lease_owner=? AND status='RUNNING' AND"
                                + " lease_until>=CURRENT_TIMESTAMP(3)",
                        Timestamp.from(Instant.now().plusSeconds(120)),
                        job.get("job_id"),
                        job.get("lease_token"),
                        job.get("lease_owner"));
        if (changed != 1) throw new IllegalStateException("REBUILD_LEASE_LOST");
    }

    public void failed(Map<String, Object> job, String error) {
        jdbc.update(
                "UPDATE analytics_rebuild_job SET status=CASE WHEN attempts<5 THEN 'PENDING' ELSE"
                    + " 'FAILED' END,error_code=?,lease_until=NULL WHERE job_id=? AND lease_token=?"
                    + " AND recovery_epoch=? AND status='RUNNING'",
                error.substring(0, Math.min(error.length(), 128)),
                job.get("job_id"),
                job.get("lease_token"),
                job.get("recovery_epoch"));
    }

    public void publish(Map<String, Object> job, List<String> replicas) {
        tx.executeWithoutResult(
                s -> {
                    String epoch = (String) job.get("recovery_epoch");
                    lockEpoch(epoch);
                    var held =
                            jdbc.queryForList(
                                    "SELECT job_id FROM analytics_rebuild_job WHERE job_id=? AND"
                                        + " lease_token=? AND lease_owner=? AND status='RUNNING'"
                                        + " AND lease_until>=CURRENT_TIMESTAMP(3) FOR UPDATE",
                                    job.get("job_id"),
                                    job.get("lease_token"),
                                    job.get("lease_owner"));
                    if (held.isEmpty()) throw new IllegalStateException("REBUILD_LEASE_LOST");
                    SourceCut next =
                            EventJson.read((String) job.get("source_cut"), SourceCut.class);
                    long start = ((Number) job.get("start_ms")).longValue(),
                            end = ((Number) job.get("end_ms")).longValue();
                    for (long window = start; window < end; window += 300000) {
                        var old =
                                jdbc.queryForList(
                                        "SELECT * FROM analytics_manifest WHERE window_start=? FOR"
                                                + " UPDATE",
                                        window);
                        long rev = 1;
                        if (!old.isEmpty()) {
                            if (epoch.equals(old.get(0).get("recovery_epoch"))
                                    && !next.covers(
                                            EventJson.read(
                                                    (String) old.get(0).get("source_cut"),
                                                    SourceCut.class)))
                                throw new IllegalStateException("INCOMPARABLE_SOURCE_CUT");
                            rev = ((Number) old.get(0).get("manifest_revision")).longValue() + 1;
                        }
                        long observed = ((Number) job.get("source_observed_at")).longValue();
                        jdbc.update(
                                "INSERT INTO"
                                    + " analytics_manifest(window_start,recovery_epoch,build_id,manifest_revision,source_cut,source_observed_at,metric_version,dataset_version,finalized,replica_ids,coverage_proof)"
                                    + " VALUES(?,?,?,?,?,?,'click-v1','detail-v1',?,?,?) ON"
                                    + " DUPLICATE KEY UPDATE"
                                    + " recovery_epoch=VALUES(recovery_epoch),build_id=VALUES(build_id),manifest_revision=VALUES(manifest_revision),source_cut=VALUES(source_cut),source_observed_at=VALUES(source_observed_at),finalized=VALUES(finalized),replica_ids=VALUES(replica_ids),coverage_proof=VALUES(coverage_proof),published_at=CURRENT_TIMESTAMP(3)",
                                window,
                                epoch,
                                job.get("build_id"),
                                rev,
                                job.get("source_cut"),
                                observed,
                                observed >= window + 300000 + 86_400_000 + 5000,
                                EventJson.write(replicas),
                                Objects.requireNonNull(
                                        job.get("coverage_proof"),
                                        "Publication requires replica content proof"));
                        Map<String, Object> publication = new LinkedHashMap<>();
                        publication.put("windowStart", window);
                        publication.put("recoveryEpoch", epoch);
                        publication.put("buildId", job.get("build_id"));
                        publication.put("manifestRevision", rev);
                        publication.put("sourceCut", next);
                        publication.put("coverageProof", job.get("coverage_proof"));
                        publication.put("replicas", replicas);
                        jdbc.update(
                                "INSERT INTO"
                                    + " analytics_publication_intent(intent_id,recovery_epoch,window_start,manifest_revision,status,manifest_payload)"
                                    + " VALUES(?,?,?,?,'PENDING',?)",
                                UUID.randomUUID().toString(),
                                epoch,
                                window,
                                rev,
                                EventJson.write(publication));
                    }
                    jdbc.update(
                            "UPDATE analytics_rebuild_job SET"
                                    + " status='PUBLISHED',updated_at=CURRENT_TIMESTAMP(3) WHERE"
                                    + " job_id=?",
                            job.get("job_id"));
                });
    }

    public Map<String, Object> job(String id) {
        var rows = jdbc.queryForList("SELECT * FROM analytics_rebuild_job WHERE job_id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown job");
        return rows.get(0);
    }

    public void verifyRecoveryPublications(String epoch) {
        assertEpoch(epoch);
        // This timestamp is initialized when reconciliation is first requested. Recovery phase,
        // lease and retry updates deliberately leave it unchanged, so retries keep the same cut.
        Timestamp started =
                jdbc.queryForObject(
                        "SELECT updated_at FROM analytics_recovery_run WHERE recovery_epoch=?"
                                + " AND status='VERIFIED'",
                        Timestamp.class,
                        epoch);
        if (started == null)
            throw new IllegalStateException("Recovery catalog/raw verification is incomplete");
        // Canonical admission closes eight minutes after a five-minute window ends, matching
        // planRequestedRepair / RebuildExecutor. Live windows remain durable repair requests;
        // requiring that global queue to empty would prevent activation under continuous traffic.
        long closedWindowEnd = Math.floorDiv(started.getTime() - 480000, 300000) * 300000;
        Long old =
                dbCount("SELECT count(*) FROM analytics_manifest WHERE recovery_epoch<>?", epoch);
        Long pending =
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_rebuild_job WHERE recovery_epoch=? AND"
                                + " start_ms<? AND status IN ('PENDING','RUNNING')",
                        Long.class,
                        epoch,
                        closedWindowEnd);
        // Do not filter by request time: a late arrival for an old window must still block until
        // its required archive cut has been rebuilt. Only windows outside the fixed cut defer.
        Long requests =
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_repair_request WHERE window_start<?",
                        Long.class,
                        closedWindowEnd);
        if (old != 0
                || pending != 0
                || requests != 0
                || hasUncoveredFailedRecoveryBuilds(epoch, closedWindowEnd))
            throw new IllegalStateException("Recovery rebuild/publication is incomplete");
    }

    private boolean hasUncoveredFailedRecoveryBuilds(String epoch, long closedWindowEnd) {
        String after = "";
        while (true) {
            var failed =
                    jdbc.queryForList(
                            "SELECT job_id,start_ms,end_ms,source_cut FROM analytics_rebuild_job"
                                    + " WHERE recovery_epoch=? AND start_ms<? AND status='FAILED'"
                                    + " AND job_id>? ORDER BY job_id LIMIT 100",
                            epoch,
                            closedWindowEnd,
                            after);
            for (var job : failed) {
                long start = ((Number) job.get("start_ms")).longValue();
                long end = ((Number) job.get("end_ms")).longValue();
                if (start < 0
                        || end <= start
                        || end - start > 86_400_000L
                        || start % 300000 != 0
                        || end % 300000 != 0) return true;
                SourceCut required =
                        EventJson.read((String) job.get("source_cut"), SourceCut.class);
                if (required.ranges().isEmpty()) return true;
                // A terminal failed attempt is retained as evidence. A different successful
                // attempt can satisfy it only through authoritative per-window publications,
                // not a PUBLISHED job status or a later creation time. publish() atomically
                // records these manifests after archive and replica-content verification.
                var published =
                        jdbc.queryForList(
                                "SELECT source_cut FROM analytics_manifest WHERE recovery_epoch=?"
                                        + " AND window_start>=? AND window_start<?",
                                epoch,
                                start,
                                end);
                if (published.size() != (end - start) / 300000) return true;
                for (var manifest : published)
                    if (!EventJson.read((String) manifest.get("source_cut"), SourceCut.class)
                            .covers(required)) return true;
            }
            if (failed.size() < 100) return false;
            after = (String) failed.get(failed.size() - 1).get("job_id");
        }
    }

    public List<Map<String, Object>> pendingPublications() {
        return jdbc.queryForList(
                "SELECT * FROM analytics_publication_intent WHERE status='PENDING' ORDER BY"
                        + " created_at LIMIT 100");
    }

    public void journaled(String id) {
        jdbc.update(
                "UPDATE analytics_publication_intent SET status='JOURNALED' WHERE intent_id=? AND"
                        + " status='PENDING'",
                id);
    }

    private Long dbCount(String sql, String epoch) {
        return jdbc.queryForObject(sql, Long.class, epoch);
    }
}
