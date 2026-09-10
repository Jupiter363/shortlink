package com.jupiter.shortlink.command.metadata;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Component
public class MetadataRuntime {
    private static final Logger log = LoggerFactory.getLogger(MetadataRuntime.class);
    static final int MAX_DRAIN_JOBS = 64;
    static final long DRAIN_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final Duration CONSUMER_CLOSE_BUDGET = Duration.ofSeconds(1);
    private final MetadataJobService jobs;
    private final SafeMetadataFetcher fetcher;
    private final Properties properties = new Properties();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<Consumer<String, String>> consumer = new AtomicReference<>();
    private final Supplier<Consumer<String, String>> consumerFactory;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers;
    private final int concurrency;
    private final String owner = UUID.randomUUID().toString();
    private final Thread intake;
    private final Counter committedBatches, failedBatches, acceptedRecords, rejectedRecords;
    private final Counter committedOffsets, failedOffsets, reconnects;
    private final Timer committedDuration, failedDuration;

    /** Retained for existing callers outside Spring. */
    public MetadataRuntime(
            MetadataJobService jobs,
            SafeMetadataFetcher fetcher,
            String bootstrap,
            String group,
            int concurrency) {
        this(jobs, fetcher, bootstrap, group, concurrency, new SimpleMeterRegistry());
    }

    @Autowired
    public MetadataRuntime(
            MetadataJobService jobs,
            SafeMetadataFetcher fetcher,
            @Value("${shortlink.kafka.bootstrap-servers}") String bootstrap,
            @Value("${shortlink.metadata.consumer-group:shortlink-metadata-v1}") String group,
            @Value("${shortlink.metadata.workers:4}") int concurrency,
            MeterRegistry registry) {
        this(jobs, fetcher, bootstrap, group, concurrency, registry, null);
    }

    MetadataRuntime(
            MetadataJobService jobs,
            SafeMetadataFetcher fetcher,
            String bootstrap,
            String group,
            int concurrency,
            MeterRegistry registry,
            Supplier<Consumer<String, String>> consumerFactory) {
        if (concurrency < 1 || concurrency > 16)
            throw new IllegalArgumentException("Invalid metadata worker budget");
        this.jobs = jobs;
        this.fetcher = fetcher;
        this.concurrency = concurrency;
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        com.jupiter.shortlink.contract.KafkaSecurity.apply(properties);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MetadataJobService.MAX_INTAKE_RECORDS);
        properties.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 1048576);
        properties.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 2097152);
        properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 120000);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        this.consumerFactory =
                consumerFactory == null ? () -> new KafkaConsumer<>(properties) : consumerFactory;
        committedBatches =
                registry.counter("shortlink.metadata.intake.batches", "outcome", "committed");
        failedBatches = registry.counter("shortlink.metadata.intake.batches", "outcome", "failed");
        acceptedRecords =
                registry.counter("shortlink.metadata.intake.records", "outcome", "accepted");
        rejectedRecords =
                registry.counter("shortlink.metadata.intake.records", "outcome", "rejected");
        committedOffsets =
                registry.counter(
                        "shortlink.metadata.intake.offset.commits", "outcome", "committed");
        failedOffsets =
                registry.counter("shortlink.metadata.intake.offset.commits", "outcome", "failed");
        reconnects = registry.counter("shortlink.metadata.intake.reconnects");
        committedDuration =
                registry.timer("shortlink.metadata.intake.duration", "outcome", "committed");
        failedDuration = registry.timer("shortlink.metadata.intake.duration", "outcome", "failed");
        workers =
                new ThreadPoolExecutor(
                        concurrency,
                        concurrency,
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        r -> {
                            Thread t = new Thread(r, "metadata-worker");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        intake = new Thread(this::consume, "metadata-inbox");
        intake.setDaemon(true);
    }

    @PostConstruct
    public void start() {
        intake.start();
    }

    private void consume() {
        while (running.get()) {
            Consumer<String, String> current = null;
            try {
                current = consumerFactory.get();
                consumer.set(current);
                current.subscribe(List.of(MetadataJobService.TOPIC));
                while (running.get()) {
                    intakePoll(current);
                }
            } catch (WakeupException e) {
                if (running.get())
                    log.warn("Metadata intake interrupted; reconnecting from committed offsets");
            } catch (Exception e) {
                log.warn("Metadata inbox commit deferred ({})", e.getClass().getSimpleName());
            } finally {
                consumer.set(null);
                if (current != null) {
                    // Kafka's no-argument close may wait far longer than application shutdown.
                    // Clear our shutdown interrupt only while performing this bounded cleanup.
                    boolean interrupted = Thread.interrupted();
                    try {
                        current.close(CONSUMER_CLOSE_BUDGET);
                    } catch (Exception e) {
                        if (running.get())
                            log.warn(
                                    "Metadata consumer close deferred ({})",
                                    e.getClass().getSimpleName());
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
            }
            // A failed batch RECREATES the consumer at committed offsets. Polling onward could lose
            // uncommitted records.
            if (running.get()) {
                reconnects.increment();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    void intakePoll(Consumer<String, String> current) {
        ConsumerRecords<String, String> records = current.poll(Duration.ofMillis(500));
        if (records.isEmpty() || !running.get()) return;
        if (records.count() > MetadataJobService.MAX_INTAKE_RECORDS)
            throw new IllegalStateException("Metadata poll exceeds budget");
        List<MetadataJobService.IntakeRecord> batch = new ArrayList<>(records.count());
        for (ConsumerRecord<String, String> record : records)
            batch.add(
                    new MetadataJobService.IntakeRecord(
                            record.value(), record.topic(), record.partition(), record.offset()));
        long started = System.nanoTime();
        MetadataJobService.IntakeResult result;
        try {
            result = jobs.acceptBatch(batch);
        } catch (RuntimeException e) {
            failedBatches.increment();
            failedDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            throw e;
        }
        committedBatches.increment();
        committedDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        acceptedRecords.increment(result.accepted());
        rejectedRecords.increment(result.rejected());
        // A concurrent shutdown may leave this committed batch to replay; that is safe.
        if (!running.get()) return;
        try {
            current.commitSync(Duration.ofSeconds(3));
            committedOffsets.increment();
        } catch (RuntimeException e) {
            failedOffsets.increment();
            throw e;
        }
    }

    @Scheduled(fixedDelayString = "${shortlink.metadata.poll-millis:1000}")
    public void poll() {
        if (!running.get() || active.size() >= concurrency) return;
        for (String id : jobs.candidates(concurrency)) {
            if (!running.get()) return;
            if (!active.add(id)) continue;
            try {
                workers.execute(() -> drain(id));
            } catch (RejectedExecutionException e) {
                active.remove(id);
                break;
            }
        }
    }

    private void drain(String first) {
        String id = first;
        long started = System.nanoTime();
        for (int completed = 0; completed < MAX_DRAIN_JOBS; completed++) {
            boolean continueDrain;
            try {
                continueDrain = running.get() && run(id);
            } finally {
                active.remove(id);
            }
            // This is a budget between jobs, not a deadline that interrupts an in-flight fetch.
            if (!continueDrain
                    || !running.get()
                    || completed + 1 >= MAX_DRAIN_JOBS
                    || System.nanoTime() - started >= DRAIN_NANOS) return;
            id = null;
            try {
                for (String candidate : jobs.candidates(concurrency)) {
                    if (!running.get()) return;
                    if (active.add(candidate)) {
                        id = candidate;
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Metadata refill deferred ({})", e.getClass().getSimpleName());
                return;
            }
            if (id == null) return;
        }
    }

    /**
     * False yields until the scheduled tick, avoiding busy retries on stale claims or DB errors.
     */
    private boolean run(String id) {
        MetadataJobService.Lease lease;
        try {
            lease = jobs.claim(id, owner);
        } catch (Exception e) {
            log.warn("Metadata claim deferred ({})", e.getClass().getSimpleName());
            return false;
        }
        // A claim already admitted before shutdown can commit; do not start a new fetch for it.
        // Its existing bounded lease is recovered by the next runtime.
        if (lease == null || !running.get()) return false;
        if (lease.attempts() > 8)
            return persistFailure(lease, new java.io.IOException("METADATA_RETRY_EXHAUSTED"));
        SafeMetadataFetcher.Metadata metadata;
        try {
            metadata = fetcher.fetch(lease.url());
        } catch (Exception e) {
            return persistFailure(lease, e);
        }
        try {
            jobs.complete(lease, metadata);
            return true;
        } catch (MetadataJobService.StaleLeaseException ignored) {
            return false;
        } catch (Exception e) {
            // A completion with an unknown commit must not be reclassified as a fetch failure.
            log.warn("Metadata completion deferred ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean persistFailure(MetadataJobService.Lease lease, Exception error) {
        try {
            jobs.failed(lease, error);
            return true;
        } catch (MetadataJobService.StaleLeaseException ignored) {
            return false;
        } catch (Exception e) {
            log.warn("Metadata retry persistence deferred ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    @PreDestroy
    public void close() {
        running.set(false);
        Consumer<String, String> current = consumer.get();
        if (current != null) current.wakeup();
        intake.interrupt();
        workers.shutdownNow();
        try {
            intake.join(3000);
            workers.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
