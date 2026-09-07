package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;

import io.minio.*;

import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Explicit integration profile: failures are real failures; no Docker availability skip. */
class AnalyticsStorageIntegrationTest {
    @Test
    void brokerReceiptArchiveCutRebuildAndEpochFencing() throws Exception {
        String mysql =
                System.getProperty(
                        "analytics.it.jdbc",
                        "jdbc:mysql://127.0.0.1:13306/shortlink_analytics_control_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        var ds = new DriverManagerDataSource(mysql, "root", "shortlink-it-only");
        var db = new JdbcTemplate(ds);
        if (!mysql.contains("/shortlink_analytics_control_it?"))
            throw new IllegalArgumentException(
                    "This fixture only accepts the dedicated disposable integration database");
        for (String table :
                List.of(
                        "analytics_rebuild_job",
                        "analytics_manifest",
                        "analytics_archive_segment",
                        "analytics_archive_progress",
                        "analytics_publication_intent",
                        "analytics_repair_request")) db.execute("DELETE FROM " + table);
        var settings =
                new WorkerSettings(
                        "localhost:19092",
                        "analytics-integration-token-1234567890",
                        "analytics-integration-hash-key-12345678901234567890",
                        "http://127.0.0.1:19000",
                        "shortlink-it",
                        "shortlink-it-only",
                        "shortlink-analytics-identity-v2-it",
                        "http://127.0.0.1:18123",
                        "shortlink_it",
                        "shortlink-it-only",
                        "shortlink_analytics_it");
        var minio =
                MinioClient.builder()
                        .endpoint(settings.objectEndpoint())
                        .credentials(settings.objectAccessKey(), settings.objectSecretKey())
                        .build();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(settings.bucket()).build()))
            minio.makeBucket(MakeBucketArgs.builder().bucket(settings.bucket()).build());
        var objects = new ObjectArchive(settings);
        var ledger = new ControlLedger(db, new DataSourceTransactionManager(ds), objects);
        String epoch = ledger.beginRecovery();
        var ch = new ClickHouseStore(settings);
        var archive = new ArchiveConsumer(settings, ledger, objects);
        String tenant = "it-" + UUID.randomUUID();
        long time = Math.floorDiv(System.currentTimeMillis() - 1_200_000, 300000) * 300000 + 10000;
        Properties p = new Properties();
        p.put("bootstrap.servers", settings.bootstrap());
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("acks", "all");
        List<RecordMetadata> receipts = new ArrayList<>();
        try (var producer = new KafkaProducer<String, String>(p)) {
            ClickEventV1 event =
                    new ClickEventV1(
                            EventIdentity.bind(time, UUID.randomUUID().toString()),
                            1,
                            time,
                            "it",
                            tenant,
                            7001,
                            "old-gid",
                            1,
                            "example.test",
                            "it",
                            1,
                            "visitor-1",
                            "127.0.0.1",
                            "Mozilla Chrome",
                            "https://example.org/path",
                            "req",
                            "trace",
                            1);
            for (int i = 0; i < 2; i++)
                receipts.add(
                        producer.send(
                                        new ProducerRecord<>(
                                                Topics.CLICK_RAW, "test", EventJson.write(event)))
                                .get());
            ClickEventV1 second =
                    new ClickEventV1(
                            EventIdentity.bind(time + 1, UUID.randomUUID().toString()),
                            1,
                            time + 1,
                            "it",
                            tenant,
                            7001,
                            "new-gid",
                            2,
                            "example.test",
                            "it",
                            1,
                            "visitor-2",
                            "127.0.0.2",
                            "Mozilla Firefox",
                            null,
                            "req2",
                            "trace2",
                            1);
            receipts.add(
                    producer.send(
                                    new ProducerRecord<>(
                                            Topics.CLICK_RAW, "test", EventJson.write(second)))
                            .get());
            var denied =
                    new GatewayRequestEventV1(
                            EventIdentity.bind(time + 2, UUID.randomUUID().toString()),
                            1,
                            time + 2,
                            "it",
                            RequestSource.REDIRECT,
                            DecisionStage.BUSINESS,
                            "GET",
                            403,
                            "RISK_DENIED",
                            tenant,
                            7001L,
                            "example.test",
                            "it",
                            3L,
                            "r",
                            "t");
            for (int i = 0; i < 2; i++)
                receipts.add(
                        producer.send(
                                        new ProducerRecord<>(
                                                Topics.GATEWAY_REQUEST,
                                                "test",
                                                EventJson.write(denied)))
                                .get());
            // A retry cannot move its logical identity into another event window. Retain the
            // legitimate original and quarantine the forged time, without a global ID index.
            String boundId = EventIdentity.bind(time + 3, UUID.randomUUID().toString());
            for (long eventTime : List.of(time + 3, time + 3, time + 300003)) {
                var bound =
                        new ClickEventV1(
                                boundId,
                                1,
                                eventTime,
                                "it",
                                tenant,
                                7002,
                                "gid",
                                1,
                                "example.test",
                                "identity",
                                1,
                                "visitor",
                                "127.0.0.3",
                                "Mozilla Chrome",
                                null,
                                "identity-request",
                                "identity-trace",
                                1);
                receipts.add(
                        producer.send(
                                        new ProducerRecord<>(
                                                Topics.CLICK_RAW, "test", EventJson.write(bound)))
                                .get());
            }
        }
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            boolean covered = false;
            while (System.nanoTime() < deadline) {
                archive.poll();
                SourceCut cut = ledger.cut();
                covered =
                        receipts.stream()
                                .allMatch(
                                        r ->
                                                cut.ranges().stream()
                                                        .anyMatch(
                                                                x ->
                                                                        x.topic().equals(r.topic())
                                                                                && x.partition()
                                                                                        == r
                                                                                                .partition()
                                                                                && x.end()
                                                                                        > r
                                                                                                .offset()));
                if (covered) break;
                Thread.sleep(100);
            }
            assertTrue(covered, "Durable archive must cover every committed Kafka receipt");
            var forgedReceipt = receipts.get(receipts.size() - 1);
            var forgedRange =
                    ledger.cut().ranges().stream()
                            .filter(
                                    r ->
                                            r.topic().equals(forgedReceipt.topic())
                                                    && r.partition() == forgedReceipt.partition())
                            .findFirst()
                            .orElseThrow();
            var forgedSegment =
                    ledger.segments(
                                    new SourceCut.Range(
                                            forgedRange.clusterId(),
                                            forgedRange.topicId(),
                                            forgedRange.topic(),
                                            forgedRange.partition(),
                                            forgedReceipt.offset(),
                                            forgedReceipt.offset() + 1))
                            .get(0);
            long quarantined = 0;
            try (var input = objects.open((String) forgedSegment.get("object_key"));
                    var reader =
                            new BufferedReader(
                                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line = BoundedLines.read(reader, 524288)) != null; ) {
                    var captured = EventJson.read(line, ArchivedEvent.class);
                    if (captured.raw().offset() == forgedReceipt.offset()) {
                        assertEquals(
                                "INVALID_EVENT_IDENTITY", captured.verified().validationResult());
                        assertEquals(
                                TimeValidation.VERSION, captured.verified().validationVersion());
                        quarantined++;
                    }
                }
            }
            assertEquals(
                    1L, quarantined, "The original immutable archive retains the rejected receipt");
            String id =
                    ledger.enqueue(
                            Math.floorDiv(time, 300000) * 300000,
                            Math.floorDiv(time, 300000) * 300000 + 300000,
                            ledger.cut());
            var job = ledger.claim("it-worker");
            assertNotNull(job);
            assertEquals(id, job.get("job_id"));
            new RebuildExecutor(ledger, objects, ch, settings).run(job);
            assertEquals("PUBLISHED", ledger.job(id).get("status"));
            List<Map<String, Object>> rows = new ArrayList<>();
            ch.query(
                    "SELECT countIf(kind='CLICK') pv,countIf(kind='REQUEST') denied FROM (SELECT"
                            + " kind,event_id FROM rebuild_input WHERE build_id="
                            + ClickHouseStore.quote(id)
                            + " AND tenant_id="
                            + ClickHouseStore.quote(tenant)
                            + " AND link_id=7001 AND validation_result='VALID' GROUP BY"
                            + " kind,event_id HAVING uniqExact(payload_hash)=1)",
                    rows::add);
            assertEquals(2L, Long.parseLong(rows.get(0).get("pv").toString()));
            assertEquals(1L, Long.parseLong(rows.get(0).get("denied").toString()));
            rows.clear();
            ch.query(
                    "SELECT sum(pv) pv FROM window_results WHERE build_id="
                            + ClickHouseStore.quote(id)
                            + " AND tenant_id="
                            + ClickHouseStore.quote(tenant)
                            + " AND link_id=7002",
                    rows::add);
            assertEquals(1L, Long.parseLong(rows.get(0).get("pv").toString()));
            long forgedStart = Math.floorDiv(time, 300000) * 300000 + 300000;
            String forgedBuild = ledger.enqueue(forgedStart, forgedStart + 300000, ledger.cut());
            var forgedJob = ledger.claim("it-worker");
            assertNotNull(forgedJob);
            assertEquals(forgedBuild, forgedJob.get("job_id"));
            new RebuildExecutor(ledger, objects, ch, settings).run(forgedJob);
            assertEquals("PUBLISHED", ledger.job(forgedBuild).get("status"));
            rows.clear();
            // Invalid-only segments need not be in the valid-window rebuild index.
            ch.query(
                    "SELECT countIf(validation_result='VALID') valid FROM rebuild_input WHERE"
                        + " build_id="
                            + ClickHouseStore.quote(forgedBuild)
                            + " AND tenant_id="
                            + ClickHouseStore.quote(tenant)
                            + " AND link_id=7002",
                    rows::add);
            assertEquals(0L, Long.parseLong(rows.get(0).get("valid").toString()));
            rows.clear();
            ch.query(
                    "SELECT sum(pv) pv FROM window_results WHERE build_id="
                            + ClickHouseStore.quote(forgedBuild)
                            + " AND tenant_id="
                            + ClickHouseStore.quote(tenant)
                            + " AND link_id=7002",
                    rows::add);
            assertEquals(0L, Long.parseLong(rows.get(0).get("pv").toString()));
            String next = ledger.beginRecovery();
            assertThrows(IllegalStateException.class, () -> ledger.heartbeat(job));
            assertNotEquals(epoch, ledger.epoch().epoch());
            archive.destroy();
            // Simulate analytics-control rollback while Kafka, immutable raw objects and ClickHouse
            // are newer.
            db.execute("DELETE FROM analytics_archive_window");
            db.execute("DELETE FROM analytics_archive_segment");
            db.execute("DELETE FROM analytics_archive_progress");
            var recovery =
                    new ArchiveRecovery(objects, ledger, db, new DataSourceTransactionManager(ds));
            recovery.reconcile(next);
            recovery.tick();
            // A new process resumes the durable catalog cursor/phase and lease after the old
            // process terminates.
            db.update(
                    "UPDATE analytics_recovery_run SET lease_until=NULL WHERE recovery_epoch=?",
                    next);
            recovery =
                    new ArchiveRecovery(objects, ledger, db, new DataSourceTransactionManager(ds));
            for (int i = 0; i < 100 && !"VERIFIED".equals(recovery.status(next).get("status")); i++)
                recovery.tick();
            recovery.requireVerified(next);
            assertTrue(
                    receipts.stream()
                            .allMatch(
                                    r ->
                                            ledger.cut().ranges().stream()
                                                    .anyMatch(
                                                            x ->
                                                                    x.topic().equals(r.topic())
                                                                            && x.partition()
                                                                                    == r.partition()
                                                                            && x.end()
                                                                                    > r.offset())),
                    "Recovered catalog must restore every raw receipt range");
            assertTrue(
                    db.queryForObject("SELECT count(*) FROM analytics_archive_window", Long.class)
                            > 0,
                    "Recovery rebuilds the time index from frozen original interpretations");
        } finally {
            archive.destroy();
        }
    }
}
