package com.jupiter.shortlink.redirect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** Transport credentials are configurable without allowing operators to bypass queue/ACK bounds. */
@ConfigurationProperties(prefix = "shortlink.redirect.kafka")
public record KafkaTransportProperties(Map<String, Object> properties) {
    public KafkaTransportProperties {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        for (String key : properties.keySet())
            if (!key.equals("security.protocol")
                    && !key.startsWith("sasl.")
                    && !key.startsWith("ssl."))
                throw new IllegalArgumentException(
                        "Only Kafka transport security properties may be overridden: " + key);
    }
}
