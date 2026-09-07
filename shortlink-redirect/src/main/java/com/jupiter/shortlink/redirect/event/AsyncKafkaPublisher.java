package com.jupiter.shortlink.redirect.event;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.config.RedirectProperties;

import io.micrometer.core.instrument.MeterRegistry;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
            Producer<String, String> clickProducer,
            Producer<String, String> resultProducer) {
        clicks = new Lane("click", clickProducer, config, meters);
        results = new Lane("result", resultProducer, config, meters);
        buckets = config.clickBuckets();
        instanceId = config.instanceId();
    }

    private static Producer<String, String> producer(
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
                                        StringSerializer.class.getName()),
                                Map.entry(
                                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                        StringSerializer.class.getName()),
                                Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
                                Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
                                Map.entry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5),
                                Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500),
                                Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000),
                                Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000),
                                Map.entry(ProducerConfig.BUFFER_MEMORY_CONFIG, c.kafkaQueueBytes()),
                                Map.entry(ProducerConfig.LINGER_MS_CONFIG, 5)));
        return new KafkaProducer<>(KafkaClientSecurity.apply(properties, transport));
    }

    @Override
    public boolean click(ClickEventV1 e) {
        return clicks.offer(Topics.CLICK_RAW, EventKeys.click(e, buckets), EventJson.write(e));
    }

    @Override
    public boolean result(GatewayRequestEventV1 e) {
        return results.offer(Topics.GATEWAY_REQUEST, EventKeys.result(e), EventJson.write(e));
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
        private final Producer<String, String> producer;
        private final ArrayBlockingQueue<Pending> queue;
        private final Semaphore slots;
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicLong attemptedCount = new AtomicLong(),
                deliveredCount = new AtomicLong(),
                failedCount = new AtomicLong(),
                rejectedCount = new AtomicLong();
        private final int capacity;
        private final long byteLimit;
        private final int eventLimit;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread worker;
        private final io.micrometer.core.instrument.Counter rejected, failed, delivered;

        Lane(
                String name,
                Producer<String, String> producer,
                RedirectProperties config,
                MeterRegistry meters) {
            this.producer = producer;
            capacity = config.kafkaQueueCapacity();
            queue = new ArrayBlockingQueue<>(capacity);
            slots = new Semaphore(capacity);
            byteLimit = config.kafkaQueueBytes();
            eventLimit = config.eventMaxBytes();
            rejected = meters.counter("shortlink.events.rejected", "lane", name);
            failed = meters.counter("shortlink.events.failed", "lane", name);
            delivered = meters.counter("shortlink.events.delivered", "lane", name);
            meters.gauge(
                    "shortlink.events.pending.bytes",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("lane", name)),
                    bytes);
            worker = new Thread(this::run, "redirect-kafka-" + name);
            worker.setDaemon(true);
            worker.start();
        }

        synchronized boolean offer(String topic, String key, String body) {
            attemptedCount.incrementAndGet();
            int size =
                    body.getBytes(StandardCharsets.UTF_8).length
                            + key.getBytes(StandardCharsets.UTF_8).length;
            if (!running.get() || size > eventLimit || !slots.tryAcquire()) {
                reject();
                return false;
            }
            if (!reserveBytes(size)) {
                slots.release();
                reject();
                return false;
            }
            Pending pending = new Pending(topic, key, body, size);
            if (!running.get() || !queue.offer(pending)) {
                release(pending);
                reject();
                return false;
            }
            return true;
        }

        private boolean reserveBytes(int size) {
            long previous;
            do {
                previous = bytes.get();
                if (previous > byteLimit - size) return false;
            } while (!bytes.compareAndSet(previous, previous + size));
            return true;
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
                    try {
                        producer.send(
                                new ProducerRecord<>(pending.topic, pending.key, pending.body),
                                (metadata, error) -> {
                                    if (callbackCompleted.compareAndSet(false, true)) {
                                        if (error == null) terminal(pending, true);
                                        else retryOrRelease(pending);
                                    }
                                });
                    } catch (Exception error) {
                        if (callbackCompleted.compareAndSet(false, true)) retryOrRelease(pending);
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
            if (running.get()
                    && pending.attempts < 3
                    && System.nanoTime() - pending.created < Duration.ofSeconds(30).toNanos()
                    && queue.offer(pending)) return;
            terminal(pending, false);
        }

        private synchronized void release(Pending pending) {
            if (pending.released.compareAndSet(false, true)) {
                bytes.addAndGet(-pending.size);
                slots.release();
            }
        }

        private synchronized void terminal(Pending pending, boolean successful) {
            if (pending.released.compareAndSet(false, true)) {
                if (successful) {
                    deliveredCount.incrementAndGet();
                    delivered.increment();
                } else {
                    failedCount.incrementAndGet();
                    failed.increment();
                }
                bytes.addAndGet(-pending.size);
                slots.release();
            }
        }

        private void reject() {
            rejectedCount.incrementAndGet();
            rejected.increment();
        }

        synchronized Map<String, Long> quality() {
            return Map.of(
                    "attempted",
                    attemptedCount.get(),
                    "delivered",
                    deliveredCount.get(),
                    "failed",
                    failedCount.get(),
                    "rejected",
                    rejectedCount.get(),
                    "pending",
                    (long) capacity - slots.availablePermits());
        }

        void close() {
            running.set(false);
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            producer.close(Duration.ofSeconds(3));
        }

        private static final class Pending {
            final String topic, key, body;
            final int size;
            final long created = System.nanoTime();
            final AtomicBoolean released = new AtomicBoolean();
            int attempts;

            Pending(String topic, String key, String body, int size) {
                this.topic = topic;
                this.key = key;
                this.body = body;
                this.size = size;
            }
        }
    }
}
