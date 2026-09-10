package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.config.RedirectProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class AsyncKafkaPublisherDiagnosticsTest {
    private static final String PREFIX = "shortlink.events.diagnostic.";

    @Test
    void slotsSnapshotSeparatesQueuedFromBlockedSendAndAllReasonsSumToTotal() throws Exception {
        var meters = new SimpleMeterRegistry();
        var producer = new BlockingProducer();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        try {
            assertTrue(lane.offer("topic", "key", "first"));
            assertTrue(producer.entered.await(2, TimeUnit.SECONDS));
            assertTrue(lane.offer("topic", "key", "second"));
            assertFalse(lane.offer("topic", "key", "third"));
            assertEquals(1, reason(meters, "slots"));
            assertEquals(2, first(meters, "reserved_count"));
            assertEquals(1, first(meters, "queued_count"));
            assertEquals(1, first(meters, "outside_queue_count"));
            assertEquals(1, first(meters, "awaiting_callback"));
            assertEquals(1, first(meters, "send_active"));
            assertEquals(-1, first(meters, "last_send_return_ago_nanos"));
            assertEquals(-1, first(meters, "last_callback_ago_nanos"));
            assertEquals(2, gauge(meters, "high.watermark", "kind", "count"));
            assertFalse(lane.offer("topic", "key", "x".repeat(17000)));
            assertEquals(1, reason(meters, "event_size"));
            assertEquals(3, first(meters, "reason_code")); // First rejection is immutable: SLOTS.
            assertEquals(2, rejectedSum(meters));
            assertEquals(rejectedSum(meters), meters.get("shortlink.events.rejected").counter().count());
            producer.release.countDown();
            await(() -> lane.quality().get("pending") == 0);
            await(() -> gauge(meters, "state", "kind", "send_active") == 0);
            assertEquals(0, gauge(meters, "state", "kind", "awaiting_callback"));
            assertEquals(0, gauge(meters, "state", "kind", "send_active"));
        } finally {
            producer.release.countDown();
            lane.close();
        }
    }

    @Test
    void bytesReasonPreservesOriginalReservationAndEffectiveLimits() {
        var meters = new SimpleMeterRegistry();
        var producer = mock(false);
        var lane = new AsyncKafkaPublisher.Lane("click", producer, config(4, 2048, 2048), meters);
        try {
            assertTrue(lane.offer("topic", "k", "x".repeat(1200)));
            await(() -> producer.history().size() == 1);
            assertFalse(lane.offer("topic", "k", "x".repeat(1200)));
            assertEquals(1, reason(meters, "bytes"));
            assertEquals(0, reason(meters, "slots"));
            assertEquals(1, first(meters, "reserved_count"));
            assertEquals(1201, first(meters, "reserved_bytes"));
            assertEquals(1201, gauge(meters, "high.watermark", "kind", "bytes"));
            assertEquals(4, gauge(meters, "limit", "kind", "count"));
            assertEquals(2048, gauge(meters, "limit", "kind", "bytes"));
            assertEquals(2048, gauge(meters, "limit", "kind", "event_bytes"));
            assertEquals(64, gauge(meters, "limit", "kind", "timing_sample_every"));
            producer.completeNext();
            assertEquals(0, lane.quality().get("pending"));
        } finally { lane.close(); }
    }

    @Test
    void closedHasPriorityAndQualityShapeRemainsExactlyCompatible() {
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", mock(true), TestConfig.defaults(), meters);
        lane.close();
        assertFalse(lane.offer("topic", "key", "x".repeat(17000)));
        assertEquals(1, reason(meters, "closed"));
        assertEquals(0, reason(meters, "event_size"));
        assertEquals(Map.of("attempted", 1L, "rejected", 1L, "delivered", 0L, "failed", 0L, "pending", 0L), lane.quality());
    }

    @Test
    void callbackThenDuplicateThenThrowDoesNotDoubleCountTerminalOrSamples() {
        var meters = new SimpleMeterRegistry();
        var producer = new MockProducer<byte[], byte[]>(false, new ByteArraySerializer(), new ByteArraySerializer()) {
            @Override public Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record, Callback callback) {
                callback.onCompletion(null, null);
                callback.onCompletion(null, new RuntimeException("not exported"));
                throw new IllegalStateException("not exported");
            }
        };
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        try {
            assertTrue(lane.offer("topic", "key", "body"));
            await(() -> counter(meters, "send.exceptions") == 1);
            await(() -> timerCount(meters, "send_call") == 1);
            assertEquals(1, lane.quality().get("delivered"));
            assertEquals(0, lane.quality().get("failed"));
            assertEquals(0, lane.quality().get("pending"));
            assertEquals(1, counter(meters, "send.calls"));
            assertEquals(1, meters.get(PREFIX + "callbacks").tag("outcome", "success").counter().count());
            assertEquals(0, meters.get(PREFIX + "callbacks").tag("outcome", "failed").counter().count());
            assertEquals(0, gauge(meters, "state", "kind", "awaiting_callback"));
            assertEquals(1, timerCount(meters, "reservation_hold"));
            assertEquals(0, timerCount(meters, "ack_terminal_lock_wait"));
            assertEquals(0, timerCount(meters, "terminal_lock_hold"));
            assertEquals(1, timerCount(meters, "send_call"));
        } finally { lane.close(); }
    }

    @Test
    void retryKeepsReservationAndSeparatesApplicationCallbackFailureFromSendException() {
        var meters = new SimpleMeterRegistry();
        var producer = mock(false);
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        try {
            assertTrue(lane.offer("topic", "key", "body"));
            await(() -> producer.history().size() == 1);
            assertTrue(producer.errorNext(new RuntimeException("not exported")));
            await(() -> producer.history().size() == 2);
            assertEquals(1, lane.quality().get("pending"));
            assertEquals(1, meters.get(PREFIX + "callbacks").tag("outcome", "failed").counter().count());
            assertEquals(0, counter(meters, "send.exceptions"));
            assertTrue(producer.completeNext());
            await(() -> timerCount(meters, "reservation_hold") == 1);
            assertEquals(2, counter(meters, "send.calls"));
            assertEquals(2, timerCount(meters, "queue_to_send"));
            assertEquals(0, gauge(meters, "state", "kind", "awaiting_callback"));
            assertEquals(0, lane.quality().get("pending"));
        } finally { lane.close(); }
    }

    @Test
    void offerStillWaitsForAdmissionMonitorWhileAckAndQualityDoNot() throws Exception {
        var meters = new SimpleMeterRegistry();
        var producer = mock(false);
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        var accepted = new AtomicBoolean();
        Thread admission = new Thread(() -> accepted.set(lane.offer("topic", "key", "body")));
        try {
            synchronized (lane) {
                admission.start();
                await(() -> admission.getState() == Thread.State.BLOCKED);
                Thread.sleep(5);
            }
            admission.join(2000);
            assertFalse(admission.isAlive());
            assertTrue(accepted.get());
            await(() -> producer.history().size() == 1);
            Thread callback = new Thread(producer::completeNext);
            synchronized (lane) {
                callback.start();
                callback.join(2000);
                assertFalse(callback.isAlive(), "successful ACK must not acquire the admission monitor");
                assertEquals(0, gauge(meters, "state", "kind", "awaiting_callback"));
                assertEquals(0, gauge(meters, "state", "kind", "terminal_active"));
                var observation = new java.util.concurrent.FutureTask<>(lane::quality);
                new Thread(observation).start();
                assertEquals(0, observation.get(2, TimeUnit.SECONDS).get("pending"));
            }
            assertTrue(meters.get(PREFIX + "duration").tag("phase", "offer_lock_wait").timer().max(TimeUnit.MILLISECONDS) >= 4);
            assertEquals(0, timerCount(meters, "ack_terminal_lock_wait"));
            assertEquals(0, timerCount(meters, "terminal_lock_hold"));
            assertEquals(1, timerCount(meters, "reservation_hold"));
            assertEquals(0, lane.quality().get("pending"));
        } finally { lane.close(); }
    }

    @Test
    void nativeMetricsHaveFixedNamesNoNativeTagsAndUnavailableIsNotZero() {
        var allowed = new MetricName("request-latency-avg", "producer-metrics", "private description", Map.of("client-id", "private-client", "host", "private-host"));
        var forbidden = new MetricName("private-extra", "producer-metrics", "private description", Map.of());
        var wrongGroup = new MetricName("batch-size-max", "producer-topic-metrics", "private description", Map.of("topic", "private-topic"));
        var producer = new MockProducer<byte[], byte[]>(true, new ByteArraySerializer(), new ByteArraySerializer()) {
            @Override public Map<MetricName, Metric> metrics() {
                return Map.of(allowed, metric(allowed, 12.5), forbidden, metric(forbidden, 999), wrongGroup, metric(wrongGroup, 999));
            }
        };
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        try {
            assertEquals(12.5, gauge(meters, "kafka", "metric", "request-latency-avg"));
            assertEquals(1, gauge(meters, "kafka.available", "metric", "request-latency-avg"));
            assertTrue(Double.isNaN(gauge(meters, "kafka", "metric", "batch-size-max")));
            assertEquals(0, gauge(meters, "kafka.available", "metric", "batch-size-max"));
            assertNull(meters.find(PREFIX + "kafka").tag("metric", "private-extra").gauge());
            for (var meter : meters.getMeters()) {
                assertFalse(meter.getId().toString().contains("private"));
                assertTrue(meter.getId().getTags().stream().allMatch(t -> java.util.Set.of("lane", "reason", "outcome", "kind", "phase", "field", "metric").contains(t.getKey())));
            }
        } finally { lane.close(); }
    }

    @Test
    void rejectionJfrContainsOnlyFixedReasonAndNumericOccupancy(@TempDir Path directory) throws Exception {
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", mock(true), TestConfig.defaults(), meters);
        Path recordingFile = directory.resolve("rejections.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("shortlink.PublisherRejection").withoutStackTrace();
            recording.start();
            assertFalse(lane.offer("private-topic", "private-key", "private-payload".repeat(2000)));
            recording.stop();
            recording.dump(recordingFile);
            var events = RecordingFile.readAllEvents(recordingFile).stream()
                    .filter(e -> e.getEventType().getName().equals("shortlink.PublisherRejection")).toList();
            assertEquals(1, events.size());
            var event = events.get(0);
            assertEquals("click", event.getString("lane"));
            assertEquals("event_size", event.getString("reason"));
            assertTrue(event.getLong("epochMillis") > 0);
            assertTrue(event.getLong("eventBytes") > 16384);
            assertEquals(0, event.getLong("reservedCount"));
            assertNull(event.getStackTrace());
            assertFalse(event.toString().contains("private-"));
        } finally { lane.close(); }
    }

    private static Metric metric(MetricName name, double value) {
        return new Metric() {
            public MetricName metricName() { return name; }
            public Object metricValue() { return value; }
        };
    }
    private static MockProducer<byte[], byte[]> mock(boolean autoComplete) {
        return new MockProducer<>(autoComplete, new ByteArraySerializer(), new ByteArraySerializer());
    }
    private static double gauge(SimpleMeterRegistry meters, String suffix, String tag, String value) {
        return meters.get(PREFIX + suffix).tag(tag, value).gauge().value();
    }
    private static double first(SimpleMeterRegistry meters, String field) { return gauge(meters, "first.rejection", "field", field); }
    private static double counter(SimpleMeterRegistry meters, String suffix) { return meters.get(PREFIX + suffix).counter().count(); }
    private static double reason(SimpleMeterRegistry meters, String reason) {
        return meters.get("shortlink.events.rejected.reason").tag("reason", reason).counter().count();
    }
    private static double rejectedSum(SimpleMeterRegistry meters) {
        return java.util.Arrays.stream(AsyncKafkaPublisher.RejectReason.values()).mapToDouble(r -> reason(meters, r.label)).sum();
    }
    private static long timerCount(SimpleMeterRegistry meters, String phase) {
        return meters.get(PREFIX + "duration").tag("phase", phase).timer().count();
    }
    private static void await(java.util.function.BooleanSupplier condition) {
        reactor.core.publisher.Flux.interval(Duration.ofMillis(2)).filter(ignored -> condition.getAsBoolean()).next().block(Duration.ofSeconds(2));
    }
    private static RedirectProperties config(int capacity, long bytes, int eventLimit) {
        var c = TestConfig.defaults();
        return new RedirectProperties(c.instanceId(), c.allowedHosts(), c.trustedProxyCidrs(), c.internalToken(),
                c.riskHashSalt(), c.commandBaseUrl(), c.authorityTtlMillis(), c.requestTimeoutMillis(), c.redisTimeoutMillis(),
                c.cacheEntries(), c.originConcurrency(), c.clusterOriginRate(), capacity, bytes, eventLimit,
                c.kafkaBootstrap(), c.clickBuckets(), c.generationPollMillis());
    }
    private static final class BlockingProducer extends MockProducer<byte[], byte[]> {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BlockingProducer() { super(false, new ByteArraySerializer(), new ByteArraySerializer()); }
        @Override public Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record, Callback callback) {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("bounded test wait");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("test interrupted"); }
            callback.onCompletion(null, null);
            return CompletableFuture.completedFuture(null);
        }
    }
}
