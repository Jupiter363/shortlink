package com.jupiter.shortlink.analytics.worker;

import com.jupiter.shortlink.contract.*;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public final class ArchiveConsumer implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(ArchiveConsumer.class);
    private final WorkerSettings settings;
    private final ControlLedger ledger;
    private final ObjectArchive archive;
    private final EventEnricher enricher;
    private KafkaConsumer<byte[], byte[]> consumer;
    private String runEpoch, cluster;
    private Map<String, String> topicIds;

    public ArchiveConsumer(WorkerSettings settings, ControlLedger ledger, ObjectArchive archive) {
        this.settings = settings;
        this.ledger = ledger;
        this.archive = archive;
        this.enricher = new EventEnricher(settings.hashKey(), 5000);
    }

    @Scheduled(fixedDelayString = "${analytics.archive.poll-delay:100}")
    public synchronized void poll() {
        try {
            if (consumer == null) open();
            ledger.assertEpoch(runEpoch);
            var records = consumer.poll(Duration.ofMillis(500));
            for (TopicPartition tp : consumer.assignment()) {
                List<ConsumerRecord<byte[], byte[]>> batch = records.records(tp);
                String topicId = topicIds.get(tp.topic());
                long end = consumer.position(tp);
                long start =
                        ledger.nextOffset(
                                cluster, topicId, tp.topic(), tp.partition(), end, runEpoch);
                if (end <= start) continue;
                StringBuilder body = new StringBuilder();
                long min = Long.MAX_VALUE, max = 0;
                Set<Long> windows = new HashSet<>();
                int archivedCount = 0;
                for (var r : batch) {
                    if (r.offset() < start) continue;
                    if (r.value() != null && r.value().length > 131072)
                        throw new IllegalStateException(
                                "Oversized raw event violates broker/topic contract");
                    var receipt =
                            new RawReceipt(
                                    cluster,
                                    topicId,
                                    tp.topic(),
                                    tp.partition(),
                                    r.offset(),
                                    r.timestamp(),
                                    r.timestampType().toString(),
                                    r.value() == null
                                            ? ""
                                            : new String(r.value(), StandardCharsets.UTF_8));
                    var captured = ArchivedEvent.capture(receipt, enricher, settings.hashKey());
                    String line = EventJson.write(captured);
                    if (line.length() > 524287)
                        throw new IllegalStateException("Enriched archive record exceeds budget");
                    if (body.length() + line.length() + 1 > 4_000_000 && archivedCount > 0) {
                        persist(
                                tp,
                                topicId,
                                start,
                                r.offset(),
                                body,
                                archivedCount,
                                min,
                                max,
                                windows);
                        start = r.offset();
                        body.setLength(0);
                        windows.clear();
                        archivedCount = 0;
                        min = Long.MAX_VALUE;
                        max = 0;
                    }
                    var interpreted = captured.verified();
                    if (interpreted.valid() && (interpreted.click() || interpreted.linkId() > 0))
                        windows.add(Math.floorDiv(interpreted.occurredAt(), 300000) * 300000);
                    archivedCount++;
                    body.append(line).append('\n');
                    min = Math.min(min, r.timestamp());
                    max = Math.max(max, r.timestamp());
                }
                // Empty spans preserve committed transaction/control-record gaps without inventing
                // event receipts.
                persist(
                        tp,
                        topicId,
                        start,
                        end,
                        body,
                        archivedCount,
                        min == Long.MAX_VALUE ? 0 : min,
                        max,
                        windows);
            }
            var ends = consumer.endOffsets(consumer.assignment(), Duration.ofSeconds(5));
            for (var tp : consumer.assignment())
                if (consumer.position(tp) == ends.get(tp))
                    ledger.observed(
                            runEpoch,
                            cluster,
                            topicIds.get(tp.topic()),
                            tp.partition(),
                            ends.get(tp),
                            System.currentTimeMillis());
            consumer.commitSync(Duration.ofSeconds(5));
        } catch (Exception e) {
            LOG.warn(
                    "Archive poll failed; no unproven source progress acknowledged: {}",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            if (consumer != null) {
                // Re-seek the durable ledger before retry; poll position may have advanced before
                // object/DB failure.
                try {
                    for (var tp : consumer.assignment())
                        consumer.seek(
                                tp,
                                ledger.nextOffset(
                                        cluster,
                                        topicIds.get(tp.topic()),
                                        tp.topic(),
                                        tp.partition(),
                                        0,
                                        runEpoch));
                } catch (Exception stale) {
                    LOG.warn(
                            "Archive remains fenced until operator restarts it with the active"
                                    + " epoch");
                }
            }
        }
    }

    private void persist(
            TopicPartition tp,
            String topicId,
            long start,
            long end,
            StringBuilder body,
            int count,
            long min,
            long max,
            Set<Long> windows) {
        if (start >= end) return;
        String checksum = EventEnricher.sha256(body.toString());
        String key =
                cluster
                        + "/"
                        + topicId
                        + "/"
                        + tp.partition()
                        + "/"
                        + start
                        + "-"
                        + end
                        + "-"
                        + checksum
                        + ".jsonl";
        archive.put(key, body.toString().getBytes(StandardCharsets.UTF_8));
        archive.put(
                "catalog/segments/"
                        + cluster
                        + "/"
                        + topicId
                        + "/"
                        + tp.partition()
                        + "/"
                        + start
                        + "-"
                        + end
                        + "-"
                        + checksum
                        + ".json",
                EventJson.write(
                                new ArchiveSegment(
                                        cluster,
                                        topicId,
                                        tp.topic(),
                                        tp.partition(),
                                        start,
                                        end,
                                        key,
                                        checksum,
                                        count,
                                        min,
                                        max))
                        .getBytes(StandardCharsets.UTF_8));
        ledger.archived(
                runEpoch,
                cluster,
                topicId,
                tp.topic(),
                tp.partition(),
                start,
                end,
                key,
                checksum,
                count,
                min,
                max,
                windows);
    }

    private void open() throws Exception {
        if (runEpoch == null) runEpoch = ledger.epoch().epoch();
        ledger.assertEpoch(runEpoch);
        Properties p = new Properties();
        KafkaSecurity.apply(p);
        p.put("bootstrap.servers", settings.bootstrap());
        try (var admin = AdminClient.create(p)) {
            cluster = admin.describeCluster().clusterId().get(10, TimeUnit.SECONDS);
            topicIds = new HashMap<>();
            admin.describeTopics(List.of(Topics.CLICK_RAW, Topics.GATEWAY_REQUEST))
                    .allTopicNames()
                    .get(10, TimeUnit.SECONDS)
                    .forEach((k, v) -> topicIds.put(k, v.topicId().toString()));
        }
        p.put("group.id", "shortlink-raw-archive-v1");
        p.put("enable.auto.commit", "false");
        p.put("isolation.level", "read_committed");
        p.put("max.poll.records", System.getProperty("analytics.archive.max-poll-records", "5000"));
        p.put("fetch.max.bytes", "33554432");
        p.put("max.poll.interval.ms", "300000");
        p.put("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        consumer = new KafkaConsumer<>(p);
        consumer.subscribe(
                List.of(Topics.CLICK_RAW, Topics.GATEWAY_REQUEST),
                new ConsumerRebalanceListener() {
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {}

                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        var first = consumer.beginningOffsets(partitions, Duration.ofSeconds(10));
                        var last = consumer.endOffsets(partitions, Duration.ofSeconds(10));
                        for (var tp : partitions) {
                            long next =
                                    ledger.nextOffset(
                                            cluster,
                                            topicIds.get(tp.topic()),
                                            tp.topic(),
                                            tp.partition(),
                                            first.get(tp),
                                            runEpoch);
                            if (next < first.get(tp) || next > last.get(tp))
                                throw new IllegalStateException(
                                        "ARCHIVE_RESTORE_GAP requires archive reconciliation, not"
                                                + " offset reset");
                            consumer.seek(tp, next);
                        }
                    }
                });
    }

    public synchronized void destroy() {
        if (consumer != null) consumer.close(Duration.ofSeconds(10));
    }
}
