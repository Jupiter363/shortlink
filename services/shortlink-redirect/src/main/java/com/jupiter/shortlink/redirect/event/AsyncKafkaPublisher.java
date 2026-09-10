package com.jupiter.shortlink.redirect.event;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.config.RedirectProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offers never call Producer.send or wait for acknowledgements. Reservations include queued and
 * in-flight bytes.
 */
public final class AsyncKafkaPublisher implements RequestEventPublisher, AutoCloseable {
    private final Lane clicks;
    private final Lane results;
    private final int buckets;
    private final String instanceId;
    private final long startedAt = System.currentTimeMillis();

    public AsyncKafkaPublisher(RedirectProperties config, MeterRegistry meters) {
        this(config, meters, Map.of());
    }

    public AsyncKafkaPublisher(
            RedirectProperties config, MeterRegistry meters, Map<String, Object> transport) {
        this(
                config,
                meters,
                producer(config, "click", transport),
                producer(config, "result", transport));
    }

    public AsyncKafkaPublisher(
            RedirectProperties config,
            MeterRegistry meters,
            Producer<byte[], byte[]> clickProducer,
            Producer<byte[], byte[]> resultProducer) {
        clicks = new Lane("click", clickProducer, config, meters);
        results = new Lane("result", resultProducer, config, meters);
        buckets = config.clickBuckets();
        instanceId = config.instanceId();
    }

    private static Producer<byte[], byte[]> producer(
            RedirectProperties c, String lane, Map<String, Object> transport) {
        Map<String, Object> properties =
                new java.util.HashMap<>(
                        Map.ofEntries(
                                Map.entry(
                                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                        c.kafkaBootstrap()),
                                Map.entry(
                                        ProducerConfig.CLIENT_ID_CONFIG,
                                        "redirect-" + c.instanceId() + "-" + lane),
                                Map.entry(
                                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                        ByteArraySerializer.class.getName()),
                                Map.entry(
                                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                        ByteArraySerializer.class.getName()),
                                Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
                                Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
                                Map.entry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5),
                                Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500),
                                Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000),
                                Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000),
                                Map.entry(ProducerConfig.BUFFER_MEMORY_CONFIG, c.kafkaQueueBytes()),
                                Map.entry(ProducerConfig.LINGER_MS_CONFIG, 5)));
        return new KafkaProducer<>(
                KafkaClientSecurity.apply(properties, transport),
                new ByteArraySerializer(),
                new ByteArraySerializer());
    }

    @Override
    public boolean click(ClickEventV1 e) {
        return clicks.offerUtf8(Topics.CLICK_RAW, EventKeys.click(e, buckets), EventJson.writeUtf8(e));
    }

    @Override
    public boolean result(GatewayRequestEventV1 e) {
        return results.offerUtf8(Topics.GATEWAY_REQUEST, EventKeys.result(e), EventJson.writeUtf8(e));
    }

    @Override
    public void close() {
        clicks.close();
        results.close();
    }

    @Override
    public Map<String, Object> quality() {
        return Map.of(
                "producerInstanceId",
                instanceId,
                "startedAt",
                startedAt,
                "observedAt",
                System.currentTimeMillis(),
                "lanes",
                Map.of("click", clicks.quality(), "result", results.quality()));
    }

    static final class Lane {
        private final Producer<byte[], byte[]> producer;
        private final ArrayBlockingQueue<Pending> queue;
        // A single immutable publication makes every quality read conserve A=D+F+R+P.
        // Successful ACKs never acquire the admission monitor.
        private final AtomicReference<LaneState> state =
                new AtomicReference<>(new LaneState(0, 0, 0, 0, 0, 0));
        private final int capacity;
        private final long byteLimit;
        private final int eventLimit;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread worker;
        private final io.micrometer.core.instrument.Counter rejected, failed, delivered;
        private final Diagnostics diagnostics;

        Lane(
                String name,
                Producer<byte[], byte[]> producer,
                RedirectProperties config,
                MeterRegistry meters) {
            this.producer = producer;
            capacity = config.kafkaQueueCapacity();
            queue = new ArrayBlockingQueue<>(capacity);
            byteLimit = config.kafkaQueueBytes();
            eventLimit = config.eventMaxBytes();
            rejected = meters.counter("shortlink.events.rejected", "lane", name);
            failed = meters.counter("shortlink.events.failed", "lane", name);
            delivered = meters.counter("shortlink.events.delivered", "lane", name);
            meters.gauge(
                    "shortlink.events.pending.bytes",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("lane", name)),
                    state,
                    value -> value.get().bytes());
            diagnostics = new Diagnostics(name, this, meters);
            worker = new Thread(this::run, "redirect-kafka-" + name);
            worker.setDaemon(true);
            worker.start();
        }

        boolean offer(String topic, String key, String body) {
            return offerUtf8(topic, key, body.getBytes(StandardCharsets.UTF_8));
        }

        // Only enclosing publisher code and the String adapter can transfer ownership here.
        // No externally supplied mutable array can enter a queued Pending.
        private boolean offerUtf8(String topic, String key, byte[] bodyBytes) {
            // Encode once, outside the admission monitor. These privately
            // owned bytes supply both the exact budget and every Kafka attempt.
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            int size = bodyBytes.length + keyBytes.length;
            LockSample sample = diagnostics.sampleOffer();
            try {
                return offerLocked(topic, keyBytes, bodyBytes, size, sample);
            } finally {
                diagnostics.recordLock(sample, "offer_lock_wait", "offer_lock_hold");
            }
        }

        // Only admissions, retry publication and the close flag share this monitor.
        // Queue publication cannot appear after close has observed an empty queue.
        private synchronized boolean offerLocked(
                String topic, byte[] key, byte[] body, int size, LockSample sample) {
            if (sample != null) sample.acquired = System.nanoTime();
            try {
            LaneState admitted;
            while (true) {
                LaneState previous = state.get();
                RejectReason reason = !running.get() ? RejectReason.CLOSED
                        : size > eventLimit ? RejectReason.EVENT_SIZE
                        : previous.pending() >= capacity ? RejectReason.SLOTS
                        : previous.bytes() > byteLimit - size ? RejectReason.BYTES : null;
                admitted = reason == null ? previous.admit(size) : previous.reject();
                if (!state.compareAndSet(previous, admitted)) continue;
                if (reason != null) {
                    reject(reason, size, admitted);
                    return false;
                }
                break;
            }
            diagnostics.highCount = Math.max(diagnostics.highCount, admitted.pending());
            diagnostics.highBytes = Math.max(diagnostics.highBytes, admitted.bytes());
            Pending pending = new Pending(topic, key, body, size, sample != null);
            if (!queue.offer(pending)) {
                // An unpublished reservation becomes one rejection in the same state CAS.
                // It was never a delivered/failed event and cannot receive a callback.
                pending.released.set(true);
                LaneState rolledBack = state.updateAndGet(value -> value.rejectReservation(size));
                reject(RejectReason.QUEUE_OFFER, size, rolledBack);
                return false;
            }
            return true;
            } finally {
                if (sample != null) sample.finished = System.nanoTime();
            }
        }

        private void run() {
            while (running.get() || !queue.isEmpty()) {
                try {
                    Pending pending = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (pending == null) continue;
                    if (!running.get()
                            || System.nanoTime() - pending.created
                                    > Duration.ofSeconds(30).toNanos()) {
                        terminal(pending, false);
                        continue;
                    }
                    pending.attempts++;
                    AtomicBoolean callbackCompleted = new AtomicBoolean();
                    long sendStarted = pending.sampled ? System.nanoTime() : 0;
                    if (pending.sampled) diagnostics.record("queue_to_send", sendStarted - pending.queuedAt);
                    diagnostics.sendCalls.increment();
                    diagnostics.sendActive.incrementAndGet();
                    diagnostics.awaitingCallback.incrementAndGet();
                    try {
                        producer.send(
                                new ProducerRecord<>(pending.topic, pending.key, pending.body),
                                (metadata, error) -> {
                                    if (callbackCompleted.compareAndSet(false, true)) {
                                        long arrived = System.nanoTime();
                                        diagnostics.lastCallback.set(arrived);
                                        diagnostics.awaitingCallback.decrementAndGet();
                                        if (error == null) {
                                            diagnostics.callbacksSuccess.increment();
                                            terminal(pending, true);
                                        } else {
                                            diagnostics.callbacksFailed.increment();
                                            retryOrRelease(pending);
                                        }
                                    }
                                });
                    } catch (Exception error) {
                        diagnostics.sendExceptions.increment();
                        if (callbackCompleted.compareAndSet(false, true)) {
                            diagnostics.awaitingCallback.decrementAndGet();
                            retryOrRelease(pending);
                        }
                    } finally {
                        long returned = System.nanoTime();
                        diagnostics.lastSendReturn.set(returned);
                        diagnostics.sendActive.decrementAndGet();
                        if (pending.sampled) diagnostics.record("send_call", returned - sendStarted);
                    }
                } catch (InterruptedException interrupted) {
                    if (!running.get()) break;
                }
            }
            Pending pending;
            while ((pending = queue.poll()) != null) {
                terminal(pending, false);
            }
        }

        private void retryOrRelease(Pending pending) {
            // Failure retries are infrequent. Serialize their publication with close,
            // while successful ACK accounting bypasses this monitor entirely.
            synchronized (this) {
                if (running.get()
                        && pending.attempts < 3
                        && System.nanoTime() - pending.created < Duration.ofSeconds(30).toNanos()) {
                    pending.queuedAt = System.nanoTime();
                    if (queue.offer(pending)) return;
                }
            }
            terminal(pending, false);
        }

        private void terminal(Pending pending, boolean successful) {
            diagnostics.terminalWaiters.incrementAndGet();
            try {
                if (!pending.released.compareAndSet(false, true)) return;
                // No external callbacks, metrics or queue operations execute inside this CAS.
                // A competing offer can retry its CAS but cannot park an ACK on its monitor.
                state.updateAndGet(value -> value.settle(pending.size, successful));
                long settledAt = System.nanoTime();
                diagnostics.lastTerminal.accumulateAndGet(settledAt, Math::max);
                // Micrometer counters follow the authoritative atomic state; a scrape may lag
                // that state briefly and must never replace quality() for conservation checks.
                if (successful) delivered.increment();
                else failed.increment();
                if (pending.sampled) diagnostics.record("reservation_hold", settledAt - pending.created);
            } finally {
                diagnostics.terminalWaiters.decrementAndGet();
            }
        }

        private void reject(RejectReason reason, int eventBytes, LaneState rejectedState) {
            rejected.increment();
            diagnostics.reject(reason, eventBytes, rejectedState);
        }

        Map<String, Long> quality() {
            LaneState value = state.get();
            return Map.of(
                    "attempted",
                    value.attempted(),
                    "delivered",
                    value.delivered(),
                    "failed",
                    value.failed(),
                    "rejected",
                    value.rejected(),
                    "pending",
                    value.pending());
        }

        private long outsideQueue() {
            // Diagnostics must not acquire the admission monitor. These two reads are
            // adjacent observations, not an atomic partition: concurrent admission or
            // completion can temporarily make the sampled queue size exceed pending.
            // quality() remains the authoritative, atomic conservation snapshot.
            return Math.max(0L, state.get().pending() - queue.size());
        }

        void close() {
            synchronized (this) {
                running.set(false);
            }
            // Never hold the admission monitor across join or Producer.close callbacks.
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            producer.close(Duration.ofSeconds(3));
        }

        private record LaneState(long attempted, long delivered, long failed, long rejected,
                                 long pending, long bytes) {
            LaneState admit(int size) {
                return new LaneState(attempted + 1, delivered, failed, rejected, pending + 1, bytes + size);
            }
            LaneState reject() {
                return new LaneState(attempted + 1, delivered, failed, rejected + 1, pending, bytes);
            }
            LaneState rejectReservation(int size) {
                return new LaneState(attempted, delivered, failed, rejected + 1, pending - 1, bytes - size);
            }
            LaneState settle(int size, boolean successful) {
                return new LaneState(attempted, delivered + (successful ? 1 : 0), failed + (successful ? 0 : 1),
                        rejected, pending - 1, bytes - size);
            }
        }

        private static final class Pending {
            final String topic;
            final byte[] key, body;
            final int size;
            final long created = System.nanoTime();
            final AtomicBoolean released = new AtomicBoolean();
            final boolean sampled;
            volatile long queuedAt = created;
            int attempts;

            Pending(String topic, byte[] key, byte[] body, int size, boolean sampled) {
                this.topic = topic;
                this.key = key;
                this.body = body;
                this.size = size;
                this.sampled = sampled;
            }
        }
    }

    enum RejectReason {
        CLOSED("closed"), EVENT_SIZE("event_size"), SLOTS("slots"), BYTES("bytes"), QUEUE_OFFER("queue_offer");
        final String label;
        RejectReason(String label) { this.label = label; }
    }

    private static final class LockSample {
        long started = System.nanoTime();
        long acquired;
        long finished;
    }

    /** Diagnostic state never authorizes admission, retry, delivery or shutdown. */
    private static final class Diagnostics {
        static final int SAMPLE_EVERY = 64;
        static final List<String> PHASES = List.of("offer_lock_wait", "offer_lock_hold", "queue_to_send",
                "send_call", "ack_terminal_lock_wait", "terminal_lock_hold", "reservation_hold");
        static final List<String> FIRST_FIELDS = List.of("present", "epoch_millis", "reason_code", "event_bytes",
                "reserved_count", "queued_count", "outside_queue_count", "reserved_bytes", "awaiting_callback",
                "send_active", "terminal_active", "last_send_return_ago_nanos", "last_callback_ago_nanos",
                "last_terminal_ago_nanos");
        final Lane lane;
        final String name;
        final AtomicLong offerSequence = new AtomicLong();
        final AtomicLong sendActive = new AtomicLong(), awaitingCallback = new AtomicLong(), terminalWaiters = new AtomicLong();
        final AtomicLong lastSendReturn = new AtomicLong(), lastCallback = new AtomicLong(), lastTerminal = new AtomicLong();
        final io.micrometer.core.instrument.Counter[] reasons = new io.micrometer.core.instrument.Counter[RejectReason.values().length];
        final io.micrometer.core.instrument.Counter sendCalls, sendExceptions, callbacksSuccess, callbacksFailed;
        final Map<String, Timer> timings = new LinkedHashMap<>();
        volatile long highCount, highBytes;
        volatile RejectionSnapshot first;
        final KafkaView kafka;

        Diagnostics(String name, Lane lane, MeterRegistry meters) {
            this.name = name;
            this.lane = lane;
            // Register the type before serving traffic; first rejection must not initialize JFR under the Lane lock.
            new RejectionEvent().isEnabled();
            for (RejectReason reason : RejectReason.values()) {
                reasons[reason.ordinal()] = meters.counter("shortlink.events.rejected.reason", "lane", name, "reason", reason.label);
            }
            sendCalls = meters.counter("shortlink.events.diagnostic.send.calls", "lane", name);
            sendExceptions = meters.counter("shortlink.events.diagnostic.send.exceptions", "lane", name);
            callbacksSuccess = meters.counter("shortlink.events.diagnostic.callbacks", "lane", name, "outcome", "success");
            callbacksFailed = meters.counter("shortlink.events.diagnostic.callbacks", "lane", name, "outcome", "failed");
            for (String phase : PHASES) {
                // The fixed schema retains the two retired ACK-monitor phases, with count=0:
                // ack_terminal_lock_wait and terminal_lock_hold have no samples in CAS mode.
                // Their empty series are not measured zero-millisecond ACK latency.
                timings.put(phase, Timer.builder("shortlink.events.diagnostic.duration")
                        .tags("lane", name, "phase", phase).register(meters));
            }
            gauge(meters, "limit", "kind", "count", () -> lane.capacity);
            gauge(meters, "limit", "kind", "bytes", () -> lane.byteLimit);
            gauge(meters, "limit", "kind", "event_bytes", () -> lane.eventLimit);
            gauge(meters, "limit", "kind", "timing_sample_every", () -> SAMPLE_EVERY);
            gauge(meters, "high.watermark", "kind", "count", () -> highCount);
            gauge(meters, "high.watermark", "kind", "bytes", () -> highBytes);
            gauge(meters, "state", "kind", "reserved", () -> lane.state.get().pending());
            gauge(meters, "state", "kind", "queued", () -> lane.queue.size());
            gauge(meters, "state", "kind", "outside_queue", lane::outsideQueue);
            gauge(meters, "state", "kind", "awaiting_callback", awaitingCallback::get);
            gauge(meters, "state", "kind", "send_active", sendActive::get);
            gauge(meters, "state", "kind", "terminal_active", terminalWaiters::get);
            gauge(meters, "progress.age", "phase", "send_return", () -> age(System.nanoTime(), lastSendReturn.get()));
            gauge(meters, "progress.age", "phase", "callback", () -> age(System.nanoTime(), lastCallback.get()));
            gauge(meters, "progress.age", "phase", "terminal", () -> age(System.nanoTime(), lastTerminal.get()));
            for (String field : FIRST_FIELDS) {
                gauge(meters, "first.rejection", "field", field, () -> firstValue(field));
            }
            kafka = new KafkaView(lane.producer);
            for (String metric : KafkaView.ALLOWED) {
                gauge(meters, "kafka", "metric", metric, () -> kafka.value(metric));
                gauge(meters, "kafka.available", "metric", metric, () -> Double.isFinite(kafka.value(metric)) ? 1 : 0);
            }
        }

        private void gauge(MeterRegistry meters, String suffix, String tag, String value, java.util.function.DoubleSupplier read) {
            Gauge.builder("shortlink.events.diagnostic." + suffix, this, ignored -> read.getAsDouble())
                    .tags("lane", name, tag, value).register(meters);
        }

        LockSample sampleOffer() {
            return (offerSequence.getAndIncrement() & (SAMPLE_EVERY - 1)) == 0 ? new LockSample() : null;
        }

        void record(String phase, long nanos) { timings.get(phase).record(Math.max(0, nanos), TimeUnit.NANOSECONDS); }

        void recordLock(LockSample sample, String waitPhase, String holdPhase) {
            if (sample != null && sample.finished != 0) {
                if (waitPhase != null) record(waitPhase, sample.acquired - sample.started);
                record(holdPhase, sample.finished - sample.acquired);
            }
        }

        // Called under the original admission monitor. Other threads' progress fields are adjacent,
        // not an atomic partition of reservations: send_active can overlap awaiting_callback.
        void reject(RejectReason reason, int eventBytes, Lane.LaneState rejectedState) {
            reasons[reason.ordinal()].increment();
            RejectionEvent event = new RejectionEvent();
            if (first == null || event.isEnabled()) {
                long now = System.nanoTime();
                long reserved = rejectedState.pending();
                long queued = lane.queue.size();
                RejectionSnapshot snapshot = new RejectionSnapshot(System.currentTimeMillis(), now, reason,
                        eventBytes, reserved, queued, reserved - queued, rejectedState.bytes(), awaitingCallback.get(),
                        sendActive.get(), terminalWaiters.get(), age(now, lastSendReturn.get()),
                        age(now, lastCallback.get()), age(now, lastTerminal.get()));
                if (first == null) first = snapshot;
                if (event.isEnabled()) {
                    event.lane = name;
                    event.reason = reason.label;
                    event.epochMillis = snapshot.epochMillis;
                    event.monotonicNanos = snapshot.monotonicNanos;
                    event.eventBytes = eventBytes;
                    event.reservedCount = reserved;
                    event.queuedCount = queued;
                    event.outsideQueueCount = snapshot.outsideQueueCount;
                    event.reservedBytes = snapshot.reservedBytes;
                    event.awaitingCallback = snapshot.awaitingCallback;
                    event.sendActive = snapshot.sendActive;
                    event.terminalActive = snapshot.terminalActive;
                    event.lastSendReturnAgoNanos = snapshot.lastSendReturnAgoNanos;
                    event.lastCallbackAgoNanos = snapshot.lastCallbackAgoNanos;
                    event.lastTerminalAgoNanos = snapshot.lastTerminalAgoNanos;
                    event.commit();
                }
            }
        }

        double firstValue(String field) {
            RejectionSnapshot value = first;
            if (value == null) return 0;
            return switch (field) {
                case "present" -> 1;
                case "epoch_millis" -> value.epochMillis;
                case "reason_code" -> value.reason.ordinal() + 1;
                case "event_bytes" -> value.eventBytes;
                case "reserved_count" -> value.reservedCount;
                case "queued_count" -> value.queuedCount;
                case "outside_queue_count" -> value.outsideQueueCount;
                case "reserved_bytes" -> value.reservedBytes;
                case "awaiting_callback" -> value.awaitingCallback;
                case "send_active" -> value.sendActive;
                case "terminal_active" -> value.terminalActive;
                case "last_send_return_ago_nanos" -> value.lastSendReturnAgoNanos;
                case "last_callback_ago_nanos" -> value.lastCallbackAgoNanos;
                case "last_terminal_ago_nanos" -> value.lastTerminalAgoNanos;
                default -> throw new IllegalArgumentException("Unknown diagnostic field");
            };
        }

        static long age(long now, long previous) { return previous == 0 ? -1 : Math.max(0, now - previous); }
    }

    private record RejectionSnapshot(long epochMillis, long monotonicNanos, RejectReason reason, long eventBytes,
            long reservedCount, long queuedCount, long outsideQueueCount, long reservedBytes, long awaitingCallback,
            long sendActive, long terminalActive, long lastSendReturnAgoNanos, long lastCallbackAgoNanos,
            long lastTerminalAgoNanos) {}

    @Name("shortlink.PublisherRejection")
    @Label("Redirect publisher rejection")
    @Category("Shortlink")
    @StackTrace(false)
    public static final class RejectionEvent extends Event {
        public String lane, reason;
        public long epochMillis, monotonicNanos, eventBytes, reservedCount, queuedCount, outsideQueueCount,
                reservedBytes, awaitingCallback, sendActive, terminalActive, lastSendReturnAgoNanos,
                lastCallbackAgoNanos, lastTerminalAgoNanos;
    }

    /** Fixed producer-wide metrics only; never export native tags or per-topic/per-node groups. */
    private static final class KafkaView {
        static final List<String> ALLOWED = List.of("batch-size-avg", "batch-size-max", "batch-split-total",
                "compression-rate-avg", "record-queue-time-avg", "record-queue-time-max",
                "request-latency-avg", "request-latency-max", "requests-in-flight", "request-total",
                "bufferpool-wait-time-total", "bufferpool-wait-ratio", "buffer-available-bytes",
                "buffer-total-bytes", "waiting-threads", "metadata-age", "metadata-wait-time-ns-total",
                "record-retry-total", "record-error-total", "record-send-total", "records-per-request-avg");
        final Producer<byte[], byte[]> producer;
        private long nextRead;
        private Map<String, Double> values = Map.of();

        KafkaView(Producer<byte[], byte[]> producer) { this.producer = producer; }

        synchronized double value(String wanted) {
            long now = System.nanoTime();
            if (nextRead == 0 || now - nextRead >= 0) {
                Map<String, Double> read = new LinkedHashMap<>();
                try {
                    producer.metrics().forEach((name, metric) -> {
                        if (name.group().equals("producer-metrics") && ALLOWED.contains(name.name())) {
                            Object raw = metric.metricValue();
                            double number = raw instanceof Number ? ((Number) raw).doubleValue() : Double.NaN;
                            if (!Double.isFinite(number)) number = Double.NaN;
                            // Duplicate base names are ambiguous, rather than a reason to publish native tags.
                            read.merge(name.name(), number, (a, b) -> Double.NaN);
                        }
                    });
                } catch (RuntimeException unavailable) {
                    read.clear();
                }
                values = Map.copyOf(read);
                nextRead = now + TimeUnit.SECONDS.toNanos(1);
            }
            return values.getOrDefault(wanted, Double.NaN);
        }
    }
}
