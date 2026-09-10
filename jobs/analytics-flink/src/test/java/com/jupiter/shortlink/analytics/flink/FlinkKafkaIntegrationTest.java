package com.jupiter.shortlink.analytics.flink;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

class FlinkKafkaIntegrationTest {
    @TempDir Path checkpoint;

    @Test
    void realKafkaCheckpointPublishesReceiptsAndDeduplicatedFullWindow() throws Exception {
        String run = UUID.randomUUID().toString(),
                bootstrap = System.getProperty("analytics.it.bootstrap", "localhost:19092");
        var env = StreamExecutionEnvironment.getExecutionEnvironment();
        AnalyticsFlinkJob.configure(
                env,
                Map.of(
                        "bootstrap",
                        bootstrap,
                        "build-id",
                        run,
                        "recovery-epoch",
                        run,
                        "checkpoint-uri",
                        checkpoint.toUri().toString(),
                        "parallelism",
                        "1",
                        "group-id",
                        "analytics-it-" + run),
                "analytics-integration-hash-key-12345678901234567890");
        env.enableCheckpointing(1000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(100);
        env.setRestartStrategy(
                org.apache.flink.api.common.restartstrategy.RestartStrategies.noRestart());
        Properties c = new Properties();
        c.put("bootstrap.servers", bootstrap);
        c.put("group.id", "analytics-result-it-" + run);
        c.put("auto.offset.reset", "latest");
        c.put("enable.auto.commit", "false");
        c.put("isolation.level", "read_committed");
        c.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        c.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        var client = env.executeAsync("analytics-it-" + run);
        var execution = client.getJobExecutionResult();
        try (var consumer = new KafkaConsumer<String, String>(c)) {
            consumer.subscribe(List.of(Topics.CLICK_ENRICHED, Topics.STATS_5M));
            long assignmentDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (consumer.assignment().isEmpty() && System.nanoTime() < assignmentDeadline)
                consumer.poll(Duration.ofMillis(200));
            assertFalse(consumer.assignment().isEmpty());
            consumer.seekToEnd(consumer.assignment());
            for (var tp : consumer.assignment()) consumer.position(tp);
            Properties p = new Properties();
            p.put("bootstrap.servers", bootstrap);
            p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            p.put("acks", "all");
            long occurredAt = System.currentTimeMillis();
            String id = EventIdentity.bind(occurredAt, UUID.randomUUID().toString());
            var event =
                    new ClickEventV1(
                            id,
                            1,
                            occurredAt,
                            "it",
                            run,
                            8001,
                            "gid",
                            1,
                            "example.test",
                            "flink",
                            1,
                            "uv",
                            "127.0.0.1",
                            "Mozilla/5.0 Chrome/100",
                            null,
                            "r",
                            "t",
                            1);
            try (var producer = new KafkaProducer<String, String>(p)) {
                for (int i = 0; i < 2; i++)
                    producer.send(
                                    new ProducerRecord<>(
                                            Topics.CLICK_RAW,
                                            EventKeys.click(event, 16),
                                            EventJson.write(event)))
                            .get();
            }
            Set<String> receipts = new HashSet<>();
            long pv = -1;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline && (receipts.size() < 2 || pv != 1)) {
                if (execution.isDone()) execution.get();
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.topic().equals(Topics.CLICK_ENRICHED)) {
                        var e = EventJson.read(r.value(), EnrichedRecord.class);
                        if (e.eventId().equals(id))
                            receipts.add(
                                    e.topicId()
                                            + ":"
                                            + e.sourcePartition()
                                            + ":"
                                            + e.sourceOffset());
                    } else {
                        var w = EventJson.read(r.value(), WindowResult.class);
                        if (w.tenantId().equals(run)) {
                            pv = w.pv();
                            assertTrue(w.revision() >= 1);
                        }
                    }
                }
            }
            if (execution.isDone()) execution.get();
            assertEquals(
                    2, receipts.size(), "The detail branch retains both committed raw receipts");
            assertEquals(1, pv, "Online full-window PV deduplicates the retry");
        } finally {
            if (!execution.isDone()) client.cancel().get();
        }
    }
}
