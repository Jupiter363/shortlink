package com.jupiter.shortlink.redirect.event;

import com.jupiter.shortlink.contract.KafkaSecurity;
import com.jupiter.shortlink.redirect.config.KafkaTransportProperties;

import java.util.Map;
import java.util.Properties;

/** Both sources are transport-only. The shared deployment secret file takes precedence. */
final class KafkaClientSecurity {
    private KafkaClientSecurity() {}

    static Properties apply(Map<String, Object> correctness, Map<String, Object> transport) {
        Properties settings = new Properties();
        settings.putAll(correctness);
        settings.putAll(new KafkaTransportProperties(transport).properties());
        KafkaSecurity.apply(settings);
        if (settings.containsKey("security.protocol")) {
            String protocol = String.valueOf(settings.get("security.protocol"));
            if (!java.util.Set.of("SSL", "SASL_SSL").contains(protocol))
                throw new IllegalArgumentException(
                        "Explicit Kafka security configuration requires TLS");
            if (settings.containsKey("ssl.endpoint.identification.algorithm")
                    && !"https"
                            .equalsIgnoreCase(
                                    String.valueOf(
                                            settings.get("ssl.endpoint.identification.algorithm"))))
                throw new IllegalArgumentException(
                        "Kafka TLS hostname verification must remain enabled");
            if ("SASL_SSL".equals(protocol)
                    && (!settings.containsKey("sasl.mechanism")
                            || !settings.containsKey("sasl.jaas.config")))
                throw new IllegalArgumentException("Kafka SASL credentials are incomplete");
        }
        return settings;
    }
}
