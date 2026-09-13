package com.jupiter.shortlink.tools;

import com.jupiter.shortlink.contract.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded raw-to-derived replay. No raw writes, archive writes, or consumer offset commits. */
public final class GeoReplay {
    public record Cut(int schemaVersion, long capturedAt, String geoVersion, SourceCut sourceCut,
                      String hashKeyFingerprint) {}
    record Output(String topic, String key, String json, String receipt, String payloadHash) {}
    private static final Set<String> RAW_TOPICS = Set.of(Topics.CLICK_RAW, Topics.GATEWAY_REQUEST);
    private static final int MAX_INPUT_BYTES = 131072;
    private static final int MAX_PREPARED_BYTES = 16 * 1024 * 1024;

    public static void main(String[] args) {
        Map<String, Object> report = new LinkedHashMap<>();
        Path reportPath = null;
        try {
            var options = options(args);
            reportPath = Path.of(required(options, "report")).toAbsolutePath();
            if (Files.exists(reportPath)) throw new IllegalArgumentException("REPORT_ALREADY_EXISTS");
            boolean apply = options.containsKey("apply");
            int maxRecords = number(options, "max-records", 5000, 1, 10000);
            int deadlineSeconds = number(options, "deadline-seconds", 120, 10, 300);
            long deadline = System.nanoTime() + Duration.ofSeconds(deadlineSeconds).toNanos();
            report.put("mode", apply ? "APPLY" : "DRY_RUN");
            report.put("startedAt", System.currentTimeMillis());
            report.put("rawWrites", 0);
            report.put("archiveWrites", 0);
            report.put("consumerOffsetCommits", 0);
            report.put("producerCreated", false);
            report.put("producerAcknowledged", 0);
            String key = Objects.requireNonNull(System.getenv("ANALYTICS_HASH_KEY"), "HASH_KEY_REQUIRED");
            String hashKeyFingerprint = EventEnricher.sha256(key);
            report.put("hashKeyFingerprint", hashKeyFingerprint);
            Cut requestedCut = null;
            String cutJson = null;
            if (!options.containsKey("capture-cut")) {
                Path cutPath = Path.of(required(options, "cut"));
                if (Files.size(cutPath) > 65536) throw new IllegalArgumentException("CUT_TOO_LARGE");
                cutJson = Files.readString(cutPath, StandardCharsets.UTF_8);
                requestedCut = EventJson.read(cutJson, Cut.class);
                // Reject key changes before XDB loading or constructing any Kafka client.
                requireHashFingerprint(requestedCut, hashKeyFingerprint);
                report.put("hashKeyFingerprintVerified", true);
            }
            try (var enriched = EventEnricher.fromEnvironment(key, 5000);
                    var legacy = new EventEnricher(key, 5000);
                    var admin = AdminClient.create(kafka(required(options, "bootstrap")))) {
                if (enriched.geoVersion().isBlank()) throw new IllegalStateException("PINNED_XDB_REQUIRED");
                report.put("geoVersion", enriched.geoVersion());
                Cut cut;
                if (options.containsKey("capture-cut")) {
                    if (apply || options.containsKey("cut")) throw new IllegalArgumentException("CAPTURE_IS_READ_ONLY");
                    cut = capture(admin, enriched.geoVersion(), hashKeyFingerprint);
                    validate(cut, enriched.geoVersion(), hashKeyFingerprint, Integer.MAX_VALUE);
                    long span = cut.sourceCut().ranges().stream().mapToLong(r -> r.end() - r.start()).sum();
                    report.put("capturedOffsetSpan", span);
                    report.put("replayRequiresNarrowerCut", span > maxRecords);
                    writeNew(Path.of(options.get("capture-cut")), EventJson.write(cut));
                    report.put("cut", cut);
                    report.put("status", "CUT_CAPTURED");
                } else {
                    cut = requestedCut;
                    validate(cut, enriched.geoVersion(), hashKeyFingerprint, maxRecords);
                    verifyTopology(admin, cut);
                    report.put("cut", cut);
                    report.put("cutSha256", EventEnricher.sha256(cutJson));
                    List<Output> prepared = read(options.get("bootstrap"), cut, enriched, legacy,
                            maxRecords, deadline, report);
                    verifyTopology(admin, cut);
                    if (apply) send(options.get("bootstrap"), prepared, deadline, report);
                    report.put("status", apply ? "COMMITTED" : "DRY_RUN_COMPLETE");
                }
            }
            report.put("finishedAt", System.currentTimeMillis());
            writeNew(reportPath, EventJson.write(report));
            System.out.println(EventJson.write(report));
        } catch (Exception failure) {
            // Exception messages from clients can include transport settings or raw data.
            report.put("status", Boolean.TRUE.equals(report.get("commitAttempted"))
                    ? "APPLY_OUTCOME_UNKNOWN" : "FAILED");
            report.put("errorClass", failure.getClass().getSimpleName());
            report.put("finishedAt", System.currentTimeMillis());
            String json = EventJson.write(report);
            if (reportPath != null && !Files.exists(reportPath)) {
                try { writeNew(reportPath, json); } catch (Exception ignored) { }
            }
            System.err.println(json);
            System.exit(1);
        }
    }

    static Map<String, String> options(String[] args) {
        Set<String> allowed = Set.of("bootstrap", "cut", "capture-cut", "report", "max-records", "deadline-seconds", "apply");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("INVALID_OPTION");
            String name = args[i].substring(2);
            if (!allowed.contains(name) || result.containsKey(name)) throw new IllegalArgumentException("INVALID_OPTION");
            String value = "apply".equals(name) ? "true" : args[++i];
            if (value.isBlank() || value.startsWith("--")) throw new IllegalArgumentException("INVALID_OPTION_VALUE");
            result.put(name, value);
        }
        return result;
    }

    static void requireHashFingerprint(Cut cut, String expectedFingerprint) {
        if (cut.schemaVersion() != 2 || cut.hashKeyFingerprint() == null
                || !cut.hashKeyFingerprint().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("CUT_RECAPTURE_REQUIRED");
        if (!cut.hashKeyFingerprint().equals(expectedFingerprint))
            throw new IllegalArgumentException("HASH_KEY_FINGERPRINT_CHANGED_RECAPTURE_REQUIRED");
    }

    static void validate(Cut cut, String version, String hashKeyFingerprint, int limit) {
        requireHashFingerprint(cut, hashKeyFingerprint);
        if (!version.equals(cut.geoVersion()) || cut.sourceCut() == null
                || cut.sourceCut().ranges().isEmpty() || cut.sourceCut().ranges().size() > 128)
            throw new IllegalArgumentException("INVALID_FIXED_CUT");
        long span = 0;
        Set<String> seen = new HashSet<>();
        Set<String> clusters = new HashSet<>();
        for (var r : cut.sourceCut().ranges()) {
            if (!RAW_TOPICS.contains(r.topic()) || r.clusterId().isBlank() || r.topicId().isBlank()
                    || !seen.add(r.topic() + "/" + r.partition())) throw new IllegalArgumentException("INVALID_SOURCE_RANGE");
            clusters.add(r.clusterId());
            span = Math.addExact(span, r.end() - r.start());
        }
        if (clusters.size() != 1 || span > limit) throw new IllegalArgumentException("SOURCE_CUT_EXCEEDS_BUDGET");
    }

    private static Cut capture(AdminClient admin, String version, String hashKeyFingerprint) throws Exception {
        String cluster = admin.describeCluster().clusterId().get(10, TimeUnit.SECONDS);
        var topics = admin.describeTopics(RAW_TOPICS).allTopicNames().get(10, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetSpec> earliest = new LinkedHashMap<>(), latest = new LinkedHashMap<>();
        for (var t : topics.values()) for (var p : t.partitions()) {
            var tp = new TopicPartition(t.name(), p.partition());
            earliest.put(tp, OffsetSpec.earliest());
            latest.put(tp, OffsetSpec.latest());
        }
        var first = admin.listOffsets(earliest).all().get(10, TimeUnit.SECONDS);
        var last = admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS);
        List<SourceCut.Range> ranges = new ArrayList<>();
        for (var tp : earliest.keySet()) ranges.add(new SourceCut.Range(cluster,
                topics.get(tp.topic()).topicId().toString(), tp.topic(), tp.partition(),
                first.get(tp).offset(), last.get(tp).offset()));
        ranges.sort(Comparator.comparing(SourceCut.Range::topic).thenComparingInt(SourceCut.Range::partition));
        return new Cut(2, System.currentTimeMillis(), version, new SourceCut(ranges), hashKeyFingerprint);
    }

    private static void verifyTopology(AdminClient admin, Cut cut) throws Exception {
        String cluster = admin.describeCluster().clusterId().get(10, TimeUnit.SECONDS);
        var topics = admin.describeTopics(RAW_TOPICS).allTopicNames().get(10, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>(), latest = new HashMap<>();
        for (var r : cut.sourceCut().ranges()) {
            var t = topics.get(r.topic());
            if (!cluster.equals(r.clusterId()) || !t.topicId().toString().equals(r.topicId())
                    || t.partitions().stream().noneMatch(p -> p.partition() == r.partition()))
                throw new IllegalStateException("SOURCE_TOPOLOGY_CHANGED");
            var tp = new TopicPartition(r.topic(), r.partition());
            earliest.put(tp, OffsetSpec.earliest()); latest.put(tp, OffsetSpec.latest());
        }
        var first = admin.listOffsets(earliest).all().get(10, TimeUnit.SECONDS);
        var last = admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS);
        for (var r : cut.sourceCut().ranges()) {
            var tp = new TopicPartition(r.topic(), r.partition());
            if (first.get(tp).offset() > r.start() || last.get(tp).offset() < r.end())
                throw new IllegalStateException("SOURCE_CUT_NO_LONGER_AVAILABLE");
        }
    }

    private static List<Output> read(String bootstrap, Cut cut, EventEnricher enricher,
            EventEnricher legacy, int limit, long deadline, Map<String, Object> report) throws Exception {
        Properties p = kafka(bootstrap);
        p.put("group.id", "shortlink-geo-replay-" + UUID.randomUUID());
        p.put("enable.auto.commit", "false"); p.put("isolation.level", "read_committed");
        p.put("auto.offset.reset", "none"); p.put("max.poll.records", "100");
        p.put("fetch.max.bytes", "4194304"); p.put("max.partition.fetch.bytes", "1048576");
        p.put("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        Map<TopicPartition, SourceCut.Range> ranges = new LinkedHashMap<>();
        cut.sourceCut().ranges().forEach(r -> ranges.put(new TopicPartition(r.topic(), r.partition()), r));
        List<Output> output = new ArrayList<>();
        int records = 0, invalid = 0, bytes = 0;
        Map<String, Integer> statusCounts = new TreeMap<>(), topicCounts = new TreeMap<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(p)) {
            consumer.assign(ranges.keySet());
            for (var entry : ranges.entrySet()) consumer.seek(entry.getKey(), entry.getValue().start());
            Set<TopicPartition> complete = new HashSet<>();
            while (complete.size() < ranges.size()) {
                checkDeadline(deadline);
                for (var entry : ranges.entrySet()) if (!complete.contains(entry.getKey())
                        && consumer.position(entry.getKey(), Duration.ofSeconds(5)) >= entry.getValue().end()) {
                    complete.add(entry.getKey()); consumer.pause(List.of(entry.getKey()));
                }
                if (complete.size() == ranges.size()) break;
                for (var r : consumer.poll(Duration.ofMillis(200))) {
                    var range = ranges.get(new TopicPartition(r.topic(), r.partition()));
                    if (r.offset() < range.start() || r.offset() >= range.end()) continue;
                    if (++records > limit || r.value() == null || r.value().length > MAX_INPUT_BYTES)
                        throw new IllegalStateException("RAW_RECORD_BUDGET_OR_SCHEMA");
                    report.put("recordsRead", records);
                    var raw = new RawReceipt(range.clusterId(), range.topicId(), r.topic(), r.partition(),
                            r.offset(), r.timestamp(), r.timestampType().toString(), new String(r.value(), StandardCharsets.UTF_8));
                    var candidate = enrich(raw, enricher, legacy);
                    if (candidate == null) { invalid++; continue; }
                    bytes = Math.addExact(bytes, candidate.json().getBytes(StandardCharsets.UTF_8).length);
                    if (bytes > MAX_PREPARED_BYTES) throw new IllegalStateException("PREPARED_BYTES_EXCEED_BUDGET");
                    output.add(candidate);
                    var e = EventJson.read(candidate.json(), EnrichedRecord.class);
                    statusCounts.merge(e.geoStatus(), 1, Integer::sum);
                    topicCounts.merge(candidate.topic(), 1, Integer::sum);
                }
            }
        }
        report.put("recordsRead", records); report.put("invalidSkipped", invalid);
        report.put("preparedRecords", output.size()); report.put("preparedBytes", bytes);
        report.put("geoStatuses", statusCounts); report.put("derivedTopics", topicCounts);
        String identities = output.stream().map(item -> item.receipt() + ":" + item.payloadHash())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        report.put("receiptIdentityDigest", EventEnricher.sha256(identities));
        return output;
    }

    static Output enrich(RawReceipt raw, EventEnricher enricher, EventEnricher legacy) {
        var e = enricher.enrich(raw); var old = legacy.enrich(raw);
        if (!e.payloadHash().equals(old.payloadHash()) || !e.eventId().equals(old.eventId())
                || !e.tenantId().equals(old.tenantId()) || e.linkId() != old.linkId()
                || !e.visitorHash().equals(old.visitorHash()) || !e.ipHash().equals(old.ipHash())
                || !e.validationResult().equals(old.validationResult()) || e.occurredAt() != old.occurredAt())
            throw new IllegalStateException("REPLAY_BASE_FACT_MISMATCH");
        if (!e.valid()) return null;
        String topic = e.click() ? Topics.CLICK_ENRICHED : Topics.REQUEST_ENRICHED;
        return new Output(topic, raw.clusterId() + ":" + raw.topicId() + ":" + raw.partition(),
                EventJson.write(e), raw.identity(), e.payloadHash());
    }

    private static void send(String bootstrap, List<Output> output, long deadline, Map<String, Object> report) throws Exception {
        Properties p = kafka(bootstrap);
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("acks", "all"); p.put("enable.idempotence", "true"); p.put("max.in.flight.requests.per.connection", "5");
        p.put("transactional.id", "shortlink-geo-replay-" + UUID.randomUUID());
        p.put("transaction.timeout.ms", "60000"); p.put("delivery.timeout.ms", "30000");
        p.put("max.block.ms", "10000"); p.put("buffer.memory", "8388608");
        report.put("producerCreated", true);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.initTransactions(); producer.beginTransaction();
            try {
                int acknowledged = 0;
                for (var item : output) {
                    checkDeadline(deadline);
                    if (!Set.of(Topics.CLICK_ENRICHED, Topics.REQUEST_ENRICHED).contains(item.topic()))
                        throw new IllegalStateException("OUTPUT_TOPIC_NOT_ALLOWED");
                    producer.send(new ProducerRecord<>(item.topic(), item.key(), item.json())).get(10, TimeUnit.SECONDS);
                    report.put("producerAcknowledged", ++acknowledged);
                }
                checkDeadline(deadline); report.put("commitAttempted", true);
                producer.commitTransaction(); report.put("transactionCommitted", true);
            } catch (Exception failure) {
                try { producer.abortTransaction(); report.put("transactionAborted", true); } catch (Exception ignored) { }
                throw failure;
            }
        }
    }

    private static Properties kafka(String bootstrap) {
        Properties p = new Properties(); KafkaSecurity.apply(p);
        p.put("bootstrap.servers", bootstrap); p.put("request.timeout.ms", "5000");
        p.put("default.api.timeout.ms", "10000"); p.put("retries", "1"); p.put("retry.backoff.ms", "100");
        return p;
    }
    private static void checkDeadline(long deadline) {
        if (System.nanoTime() >= deadline) throw new IllegalStateException("REPLAY_DEADLINE_EXCEEDED");
    }
    private static String required(Map<String, String> args, String key) {
        String value = args.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("REQUIRED_" + key);
        return value;
    }
    private static int number(Map<String, String> args, String name, int fallback, int min, int max) {
        int n = Integer.parseInt(args.getOrDefault(name, Integer.toString(fallback)));
        if (n < min || n > max) throw new IllegalArgumentException("INVALID_BUDGET"); return n;
    }
    private static void writeNew(Path path, String value) throws Exception {
        path = path.toAbsolutePath(); Files.createDirectories(path.getParent());
        Files.writeString(path, value + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
