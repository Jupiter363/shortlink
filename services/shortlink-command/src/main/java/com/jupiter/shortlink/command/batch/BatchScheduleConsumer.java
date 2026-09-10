package com.jupiter.shortlink.command.batch;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Loss or duplication of a scheduling hint cannot lose or duplicate a business job. */
@Component
public class BatchScheduleConsumer {
    public static final String TOPIC = "shortlink.batch.ready.v1";
    private static final Logger log = LoggerFactory.getLogger(BatchScheduleConsumer.class);
    private final BatchJobService jobs;
    private final BatchWorker worker;
    private final ObjectMapper json;
    private final Properties properties = new Properties();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<KafkaConsumer<String, String>> active = new AtomicReference<>();
    private final Thread thread;

    public BatchScheduleConsumer(
            BatchJobService jobs,
            BatchWorker worker,
            ObjectMapper json,
            @Value("${shortlink.kafka.bootstrap-servers}") String bootstrap,
            @Value("${shortlink.batch.consumer-group:shortlink-batch-scheduler-v1}") String group) {
        this.jobs = jobs;
        this.worker = worker;
        this.json = json;
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        com.jupiter.shortlink.contract.KafkaSecurity.apply(properties);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 32);
        properties.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 1048576);
        properties.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 2097152);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        thread = new Thread(this::consume, "batch-schedule-hints");
        thread.setDaemon(true);
    }

    @PostConstruct
    public void start() {
        thread.start();
    }

    private void consume() {
        while (running.get()) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
                active.set(consumer);
                consumer.subscribe(List.of(TOPIC));
                while (running.get()) {
                    var records = consumer.poll(Duration.ofMillis(500));
                    for (var record : records) hint(record.value());
                    if (!records.isEmpty()) consumer.commitSync(Duration.ofSeconds(3));
                }
            } catch (WakeupException e) {
                if (running.get()) log.warn("Batch scheduling consumer interrupted");
            } catch (Exception e) {
                log.warn(
                        "Batch scheduling uses durable DB polling while Kafka intake recovers ({})",
                        e.getClass().getSimpleName());
            } finally {
                active.set(null);
            }
            if (running.get())
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
        }
    }

    void hint(String payload) {
        if (payload == null || payload.length() > 2048) return;
        String job;
        long tenant;
        try {
            var event = json.readTree(payload);
            if (event.path("schemaVersion").asInt() != 1) return;
            job = event.path("jobId").asText();
            tenant = Long.parseLong(event.path("tenantId").asText());
        } catch (Exception e) {
            return;
        }
        var candidate = jobs.hintCandidate(job, tenant);
        if (candidate != null) worker.offer(candidate);
    }

    @PreDestroy
    public void close() {
        running.set(false);
        var consumer = active.get();
        if (consumer != null) consumer.wakeup();
        thread.interrupt();
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
