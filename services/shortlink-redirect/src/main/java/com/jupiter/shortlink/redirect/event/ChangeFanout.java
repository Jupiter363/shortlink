package com.jupiter.shortlink.redirect.event;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.cache.RouteResolver;
import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.redirect.risk.PolicyResolver;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * A stable, unique group per instance broadcasts every invalidation to every process. Authority
 * leases bound gaps.
 */
public final class ChangeFanout implements SmartLifecycle {
    private final RedirectProperties config;
    private final RouteResolver routes;
    private final PolicyResolver policies;
    private final Map<String, Object> transport;
    private final Function<Properties, Consumer<String, String>> consumerFactory;
    private volatile boolean running;
    private volatile boolean connected;
    private Consumer<String, String> consumer;
    private Thread worker;

    public ChangeFanout(RedirectProperties config, RouteResolver routes, PolicyResolver policies) {
        this(config, routes, policies, Map.of());
    }

    public ChangeFanout(
            RedirectProperties config,
            RouteResolver routes,
            PolicyResolver policies,
            Map<String, Object> transport) {
        this(config, routes, policies, transport, KafkaConsumer::new);
    }

    ChangeFanout(RedirectProperties config, RouteResolver routes, PolicyResolver policies,
                 Map<String, Object> transport,
                 Function<Properties, Consumer<String, String>> consumerFactory) {
        this.config = config;
        this.routes = routes;
        this.policies = policies;
        this.transport = Map.copyOf(transport);
        this.consumerFactory = consumerFactory;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        Map<String, Object> settings =
                new java.util.HashMap<>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                config.kafkaBootstrap(),
                                ConsumerConfig.GROUP_ID_CONFIG,
                                "redirect-invalidation-" + config.instanceId(),
                                ConsumerConfig.CLIENT_ID_CONFIG,
                                "redirect-invalidation-" + config.instanceId(),
                                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                StringDeserializer.class.getName(),
                                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                StringDeserializer.class.getName(),
                                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest",
                                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                                200,
                                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                                300000));
        consumer = consumerFactory.apply(KafkaClientSecurity.apply(settings, transport));
        connected = false;
        running = true;
        worker = new Thread(this::run, "redirect-change-fanout");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        try {
            var bootstrap = new InvalidationBootstrap(consumer, () -> {
                routes.invalidateAll();
                policies.invalidateAll();
            }, () -> connected = false);
            consumer.subscribe(InvalidationBootstrap.TOPICS, bootstrap);
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (!running) break;
                if (bootstrap.discardFetchedBootstrapBatch()) {
                    connected = false;
                    continue;
                }
                boolean batchSucceeded = true;
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        if (Topics.ROUTE_CHANGE.equals(record.topic())) {
                            RouteChangeV1 event =
                                    EventJson.read(record.value(), RouteChangeV1.class);
                            if (event.domainNorm() == null || event.shortUri() == null) {
                                routes.invalidateAll();
                            } else
                                routes.invalidate(
                                                event.domainNorm(),
                                                event.shortUri(),
                                                event.routeVersion())
                                        .block(Duration.ofMillis(config.requestTimeoutMillis()));
                        } else {
                            RiskPolicyChangeV1 event =
                                    EventJson.read(record.value(), RiskPolicyChangeV1.class);
                            policies.invalidate(event.resourceKey(), event.policyRevision());
                        }
                    } catch (Exception error) {
                        // Invalid/unavailable hints cannot establish authority; drop all local
                        // proofs and retry this offset.
                        routes.invalidateAll();
                        policies.invalidateAll();
                        connected = false;
                        batchSucceeded = false;
                        for (var partition : records.partitions())
                            consumer.seek(partition, records.records(partition).get(0).offset());
                        break;
                    }
                }
                if (batchSucceeded && !records.isEmpty())
                    consumer.commitSync(Duration.ofSeconds(2));
                connected = running && batchSucceeded && bootstrap.positionsReady()
                        && !consumer.assignment().isEmpty();
            }
        } catch (WakeupException ignored) {
        } catch (Exception failure) {
            connected = false;
        } finally {
            connected = false;
            try {
                consumer.close(Duration.ofSeconds(3));
            } finally {
                running = false;
            }
        }
    }

    public boolean connected() {
        return connected;
    }

    @Override
    public synchronized void stop() {
        running = false;
        connected = false;
        if (consumer != null) consumer.wakeup();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
