package com.jupiter.shortlink.analytics.worker;

import com.jupiter.shortlink.contract.*;

import org.apache.kafka.clients.consumer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/** Durable, coalesced window requests. No event or repair is sent back to the live click topic. */
@Component
public final class RepairPlanner implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(RepairPlanner.class);
    private final ControlLedger ledger;
    private final WorkerSettings settings;
    private KafkaConsumer<String, String> consumer;
    private String runEpoch;

    public RepairPlanner(ControlLedger l, WorkerSettings s) {
        ledger = l;
        settings = s;
    }

    @Scheduled(fixedDelayString = "${analytics.repair.poll-delay:3000}")
    public synchronized void poll() {
        try {
            if (runEpoch == null) runEpoch = ledger.epoch().epoch();
            ledger.assertEpoch(runEpoch);
            if (consumer == null) {
                Properties p = new Properties();
                KafkaSecurity.apply(p);
                p.put("bootstrap.servers", settings.bootstrap());
                p.put("group.id", "shortlink-repair-planner-v1");
                p.put("enable.auto.commit", "false");
                p.put("isolation.level", "read_committed");
                p.put("auto.offset.reset", "earliest");
                p.put("max.poll.records", "200");
                p.put(
                        "key.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer");
                p.put(
                        "value.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer");
                consumer = new KafkaConsumer<>(p);
                consumer.subscribe(List.of(Topics.CLICK_LATE));
            }
            var records = consumer.poll(Duration.ofMillis(100));
            for (var r : records) {
                EnrichedRecord e = EventJson.read(r.value(), EnrichedRecord.class);
                if (e.valid() && e.click()) ledger.requestRepair(e);
            }
            consumer.commitSync(Duration.ofSeconds(5));
            ledger.requestNewestClosedWindow();
            ledger.planRequestedRepair();
        } catch (Exception e) {
            LOG.warn("Repair planning paused: {}", e.getClass().getSimpleName());
            if (consumer != null) {
                consumer.close(Duration.ofSeconds(5));
                consumer = null;
            }
        }
    }

    public synchronized void destroy() {
        if (consumer != null) consumer.close(Duration.ofSeconds(5));
    }
}
