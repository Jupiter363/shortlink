package com.jupiter.shortlink.contract;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Properties;

class KafkaSecurityTest {
    @TempDir Path directory;

    @Test
    void transportCannotOverrideReliabilityBudgets() throws Exception {
        Path file = directory.resolve("kafka.properties");
        Files.writeString(file, "security.protocol=SSL\nacks=0\n");
        var p = new Properties();
        p.put("acks", "all");
        assertThrows(IllegalArgumentException.class, () -> KafkaSecurity.apply(p, file));
        assertEquals("all", p.getProperty("acks"));
    }

    @Test
    void tlsSettingsApplyWithoutLeakingSecrets() throws Exception {
        Path file = directory.resolve("kafka.properties");
        Files.writeString(
                file,
                "security.protocol=SASL_SSL\n"
                        + "sasl.mechanism=SCRAM-SHA-512\n"
                        + "sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule"
                        + " required username=\"u\" password=\"secret\";\n");
        var p = new Properties();
        KafkaSecurity.apply(p, file);
        assertEquals("SASL_SSL", p.getProperty("security.protocol"));
        assertTrue(p.getProperty("sasl.jaas.config").contains("secret"));
    }
}
