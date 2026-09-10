package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class KafkaClientSecurityTest {
    @Test
    void transportCannotOverrideAcknowledgementOrResourceBudgets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> KafkaClientSecurity.apply(Map.of("acks", "all"), Map.of("acks", "0")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        KafkaClientSecurity.apply(
                                Map.of("buffer.memory", 1024),
                                Map.of("buffer.memory", Long.MAX_VALUE)));
        var properties =
                KafkaClientSecurity.apply(
                        Map.of("acks", "all", "buffer.memory", 1024),
                        Map.of("security.protocol", "SSL"));
        assertEquals("all", properties.get("acks"));
        assertEquals(1024, properties.get("buffer.memory"));
        assertEquals("SSL", properties.get("security.protocol"));
    }

    @Test
    void explicitSecurityCannotDisableTlsOrHostnameVerification() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        KafkaClientSecurity.apply(
                                Map.of(), Map.of("security.protocol", "PLAINTEXT")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        KafkaClientSecurity.apply(
                                Map.of(),
                                Map.of(
                                        "security.protocol",
                                        "SSL",
                                        "ssl.endpoint.identification.algorithm",
                                        "")));
    }
}
