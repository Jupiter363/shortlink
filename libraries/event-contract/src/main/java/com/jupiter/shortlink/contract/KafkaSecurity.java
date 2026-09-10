package com.jupiter.shortlink.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Shared transport-only settings. No Kafka or Spring dependency, no secret values in diagnostics.
 */
public final class KafkaSecurity {
    private KafkaSecurity() {}

    private static final Set<String> ALLOWED =
            Set.of(
                    "security.protocol",
                    "sasl.mechanism",
                    "sasl.jaas.config",
                    "sasl.kerberos.service.name",
                    "ssl.protocol",
                    "ssl.enabled.protocols",
                    "ssl.cipher.suites",
                    "ssl.endpoint.identification.algorithm",
                    "ssl.truststore.location",
                    "ssl.truststore.password",
                    "ssl.truststore.type",
                    "ssl.truststore.certificates",
                    "ssl.keystore.location",
                    "ssl.keystore.password",
                    "ssl.keystore.type",
                    "ssl.keystore.key",
                    "ssl.keystore.certificate.chain",
                    "ssl.key.password");

    public static void apply(Properties target) {
        String file = System.getenv("KAFKA_SECURITY_PROPERTIES");
        if (file == null || file.isBlank()) return;
        apply(target, Path.of(file));
    }

    public static void apply(Properties target, Path file) {
        Objects.requireNonNull(target);
        if (!file.isAbsolute())
            throw new IllegalArgumentException(
                    "KAFKA_SECURITY_PROPERTIES must be an absolute file path");
        Properties transport = new Properties();
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 65536)
                throw new IOException("Invalid transport configuration file");
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                transport.load(reader);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Kafka transport configuration is unavailable");
        }
        for (String key : transport.stringPropertyNames())
            if (!ALLOWED.contains(key))
                throw new IllegalArgumentException(
                        "Unsupported Kafka transport configuration key: " + key);
        String protocol = transport.getProperty("security.protocol");
        if (protocol == null || !Set.of("SSL", "SASL_SSL").contains(protocol))
            throw new IllegalArgumentException(
                    "Configured Kafka transport requires SSL or SASL_SSL");
        if ("SASL_SSL".equals(protocol)
                && (!transport.containsKey("sasl.mechanism")
                        || !transport.containsKey("sasl.jaas.config")))
            throw new IllegalArgumentException(
                    "SASL transport requires mechanism and JAAS configuration");
        if (transport.containsKey("ssl.endpoint.identification.algorithm")
                && !"https"
                        .equalsIgnoreCase(
                                transport.getProperty("ssl.endpoint.identification.algorithm")))
            throw new IllegalArgumentException(
                    "Kafka TLS hostname verification must remain enabled");
        target.putAll(transport);
    }
}
