package com.jupiter.shortlink.analytics.flink;

import com.jupiter.shortlink.analytics.flink.process.*;
import com.jupiter.shortlink.analytics.flink.source.BrokerReceiptDeserializer;
import com.jupiter.shortlink.contract.*;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class AnalyticsFlinkJob {
    public record Output(String topic, String key, String value) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2)
            opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        configure(
                env,
                opts,
                Objects.requireNonNull(System.getenv("ANALYTICS_HASH_KEY"), "ANALYTICS_HASH_KEY"));
        env.execute("shortlink-analytics-" + required(opts, "build-id"));
    }

    public static void configure(
            StreamExecutionEnvironment env, Map<String, String> opts, String hashKey)
            throws Exception {
        String bootstrap = required(opts, "bootstrap"),
                build = required(opts, "build-id"),
                epoch = required(opts, "recovery-epoch");
        String checkpoint = required(opts, "checkpoint-uri");
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        KafkaSecurity.apply(props);
        Map<String, String> topicIds = new HashMap<>();
        String cluster;
        try (AdminClient admin = AdminClient.create(props)) {
            cluster = admin.describeCluster().clusterId().get(15, TimeUnit.SECONDS);
            var descriptions =
                    admin.describeTopics(List.of(Topics.CLICK_RAW, Topics.GATEWAY_REQUEST))
                            .allTopicNames()
                            .get(15, TimeUnit.SECONDS);
            descriptions.forEach((k, v) -> topicIds.put(k, v.topicId().toString()));
        }
        env.setParallelism(Integer.parseInt(opts.getOrDefault("parallelism", "2")));
        env.enableCheckpointing(30000, CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setCheckpointTimeout(300000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(10000);
        env.getCheckpointConfig().setCheckpointStorage(checkpoint);
        env.getCheckpointConfig()
                .enableExternalizedCheckpoints(
                        org.apache.flink.streaming.api.environment.CheckpointConfig
                                .ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        env.setStateBackend(new EmbeddedRocksDBStateBackend(true));
        env.setRestartStrategy(
                RestartStrategies.fixedDelayRestart(
                        10, org.apache.flink.api.common.time.Time.seconds(10)));
        var source =
                KafkaSource.<String>builder()
                        .setProperties(props)
                        .setBootstrapServers(bootstrap)
                        .setTopics(Topics.CLICK_RAW, Topics.GATEWAY_REQUEST)
                        .setGroupId(opts.getOrDefault("group-id", "shortlink-analytics-v1"))
                        .setStartingOffsets(
                                OffsetsInitializer.committedOffsets(
                                        org.apache.kafka.clients.consumer.OffsetResetStrategy
                                                .EARLIEST))
                        .setProperty("isolation.level", "read_committed")
                        .setProperty("partition.discovery.interval.ms", "10000")
                        .setDeserializer(new BrokerReceiptDeserializer(cluster, topicIds))
                        .build();
        var interpreted =
                env.fromSource(source, WatermarkStrategy.noWatermarks(), "raw-kafka")
                        .uid("raw-kafka-v1")
                        .process(new InterpretEvents(hashKey))
                        .name("interpret-and-detail-branch")
                        .uid("interpret-v1");
        var details =
                interpreted
                        .map(
                                v -> {
                                    var r = EventJson.read(v, EnrichedRecord.class);
                                    return EventJson.write(
                                            new Output(
                                                    r.click()
                                                            ? Topics.CLICK_ENRICHED
                                                            : Topics.REQUEST_ENRICHED,
                                                    r.clusterId()
                                                            + ":"
                                                            + r.topicId()
                                                            + ":"
                                                            + r.sourcePartition(),
                                                    v));
                                })
                        .returns(String.class);
        var online =
                interpreted
                        .getSideOutput(InterpretEvents.ONLINE)
                        .keyBy(
                                v -> {
                                    var r = EventJson.read(v, EnrichedRecord.class);
                                    return r.tenantId() + ":" + r.eventId();
                                })
                        .process(new BoundedDeduplication())
                        .uid("dedup-10m-v1");
        var partial =
                online.keyBy(
                                v -> {
                                    var r = EventJson.read(v, EnrichedRecord.class);
                                    return r.tenantId()
                                            + ":"
                                            + r.linkId()
                                            + ":"
                                            + Math.floorDiv(r.occurredAt(), 300000)
                                            + ":"
                                            + EventKeys.bucket(r.eventId(), 16);
                                })
                        .process(new PartialWindows(build, epoch))
                        .uid("partial-windows-v1");
        var global =
                partial.keyBy(
                                v -> {
                                    var r = EventJson.read(v, WindowResult.class);
                                    return r.tenantId() + ":" + r.linkId() + ":" + r.windowStart();
                                })
                        .process(new MergeWindows())
                        .uid("merge-windows-v1");
        var windows =
                global.map(
                                v -> {
                                    var r = EventJson.read(v, WindowResult.class);
                                    return EventJson.write(
                                            new Output(
                                                    Topics.STATS_5M,
                                                    r.tenantId()
                                                            + ":"
                                                            + r.linkId()
                                                            + ":"
                                                            + r.windowStart(),
                                                    v));
                                })
                        .returns(String.class);
        var signals =
                global.filter(v -> EventJson.read(v, WindowResult.class).pv() >= 1000)
                        .map(
                                v ->
                                        EventJson.write(
                                                new Output(
                                                        Topics.RISK_SIGNAL,
                                                        EventJson.read(v, WindowResult.class)
                                                                .tenantId(),
                                                        v)))
                        .returns(String.class);
        var late =
                interpreted
                        .getSideOutput(InterpretEvents.LATE)
                        .map(
                                v ->
                                        EventJson.write(
                                                new Output(
                                                        Topics.CLICK_LATE,
                                                        EventJson.read(v, EnrichedRecord.class)
                                                                .eventId(),
                                                        v)))
                        .returns(String.class);
        var invalid =
                interpreted
                        .getSideOutput(InterpretEvents.INVALID)
                        .union(online.getSideOutput(InterpretEvents.INVALID))
                        .map(v -> EventJson.write(new Output(Topics.CLICK_DLQ, "isolated", v)))
                        .returns(String.class);
        Properties producer = new Properties();
        KafkaSecurity.apply(producer);
        producer.put("transaction.timeout.ms", "900000");
        producer.put("acks", "all");
        producer.put("enable.idempotence", "true");
        var sink =
                KafkaSink.<String>builder()
                        .setBootstrapServers(bootstrap)
                        .setKafkaProducerConfig(producer)
                        .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
                        .setTransactionalIdPrefix("analytics-" + build + "-output")
                        .setRecordSerializer(
                                (KafkaRecordSerializationSchema<String>)
                                        (value, context, timestamp) -> {
                                            Output o = EventJson.read(value, Output.class);
                                            return new ProducerRecord<byte[], byte[]>(
                                                    o.topic(),
                                                    o.key().getBytes(StandardCharsets.UTF_8),
                                                    o.value().getBytes(StandardCharsets.UTF_8));
                                        })
                        .build();
        details.union(windows, signals, late, invalid).sinkTo(sink).uid("derived-kafka-eos-v1");
    }

    private static String required(Map<String, String> options, String key) {
        String v = options.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return v;
    }
}
