package com.jupiter.shortlink.redirect.membership;

import com.jupiter.shortlink.contract.Topics;
import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.redirect.event.KafkaClientSecurity;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/** Independent per-instance hint subscription. Polling the registry remains the authority. */
public final class MembershipHintConsumer implements SmartLifecycle {
    private final LocalRouteMembership membership;
    private final RouteMembershipProperties options;
    private final RedirectProperties config;
    private final Map<String, Object> transport;
    private final Function<Properties, Consumer<String, String>> consumerFactory;
    private volatile boolean running;
    private volatile long lifecycle;
    private volatile Consumer<String, String> consumer;

    public MembershipHintConsumer(
            LocalRouteMembership membership,
            RouteMembershipProperties options,
            RedirectProperties config,
            Map<String, Object> transport) {
        this(membership, options, config, transport, KafkaConsumer::new);
    }

    MembershipHintConsumer(
            LocalRouteMembership membership,
            RouteMembershipProperties options,
            RedirectProperties config,
            Map<String, Object> transport,
            Function<Properties, Consumer<String, String>> consumerFactory) {
        this.membership = membership;
        this.options = options;
        this.config = config;
        this.transport = Map.copyOf(transport);
        this.consumerFactory = consumerFactory;
    }

    @Override
    public synchronized void start() {
        if (running || !options.enabled() || !options.kafkaHintsEnabled()) return;
        running = true;
        long token = ++lifecycle;
        Thread thread = new Thread(() -> run(token), "redirect-membership-hints");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(long token) {
        Consumer<String, String> instance = null;
        try {
            instance =
                    consumerFactory.apply(
                            KafkaClientSecurity.apply(
                                    Map.ofEntries(
                                            Map.entry(
                                                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                                    config.kafkaBootstrap()),
                                            Map.entry(
                                                    ConsumerConfig.GROUP_ID_CONFIG,
                                                    "redirect-membership-" + config.instanceId()),
                                            Map.entry(
                                                    ConsumerConfig.CLIENT_ID_CONFIG,
                                                    "redirect-membership-" + config.instanceId()),
                                            Map.entry(
                                                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                                    StringDeserializer.class.getName()),
                                            Map.entry(
                                                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                                    StringDeserializer.class.getName()),
                                            Map.entry(
                                                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true),
                                            Map.entry(
                                                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                                    "latest"),
                                            Map.entry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100),
                                            Map.entry(
                                                    ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 262144),
                                            Map.entry(
                                                    ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,
                                                    262144)),
                                    transport));
            synchronized (this) {
                if (!running || lifecycle != token) return;
                consumer = instance;
            }
            instance.subscribe(List.of(Topics.ROUTE_MEMBERSHIP));
            while (running && lifecycle == token) {
                var records = instance.poll(Duration.ofMillis(500));
                // Duplicates, gaps, malformed payloads and old/new generations only wake DB sync.
                // Neither offset coverage nor topic connectivity grants a denial permission.
                if (!records.isEmpty()) membership.hint();
            }
        } catch (WakeupException stopped) {
            // DB polling is independent of this optional acceleration path.
        } catch (RuntimeException unavailable) {
            // Polling remains live; no cached Bloom coverage is inferred from this consumer.
        } finally {
            try {
                if (instance != null) instance.close(Duration.ofSeconds(2));
            } finally {
                synchronized (this) {
                    if (lifecycle == token) {
                        running = false;
                        consumer = null;
                    }
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        lifecycle++;
        Consumer<String, String> instance = consumer;
        consumer = null;
        if (instance != null) instance.wakeup();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
