package com.jupiter.shortlink.redirect.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedirectPropertiesBindingTest {
    @Test
    void binderUsesCanonicalConstructorAndAppliesEndToEndDefault() {
        RedirectProperties properties = bind(Map.of());

        assertEquals(500, properties.requestTimeoutMillis());
        assertEquals(900, properties.endToEndTimeoutMillis());
        assertEquals(1000, properties.authorityTtlMillis());
    }

    @Test
    void binderRejectsEndToEndBudgetAtOrBelowIoBudget() {
        assertThrows(Exception.class, () -> bind(Map.of(
                "shortlink.redirect.end-to-end-timeout-millis", "500")));
    }

    @Test
    void binderRejectsEndToEndBudgetAtOrAboveAuthorityTtl() {
        assertThrows(Exception.class, () -> bind(Map.of(
                "shortlink.redirect.end-to-end-timeout-millis", "1000")));
    }

    @Test
    void compatibilityConstructorDerivesBudgetBelowShorterTtl() {
        RedirectProperties baseline = bind(Map.of());
        RedirectProperties properties = new RedirectProperties(
                baseline.instanceId(), baseline.allowedHosts(), baseline.trustedProxyCidrs(),
                baseline.internalToken(), baseline.riskHashSalt(), baseline.commandBaseUrl(),
                800, 500, baseline.redisTimeoutMillis(), baseline.cacheEntries(),
                baseline.originConcurrency(), baseline.clusterOriginRate(), baseline.kafkaQueueCapacity(),
                baseline.kafkaQueueBytes(), baseline.eventMaxBytes(), baseline.kafkaBootstrap(),
                baseline.clickBuckets(), baseline.generationPollMillis());

        assertEquals(799, properties.endToEndTimeoutMillis());
    }

    private static RedirectProperties bind(Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>();
        values.put("shortlink.redirect.instance-id", "binding-test");
        values.put("shortlink.redirect.allowed-hosts[0]", "localhost:19080");
        values.put("shortlink.redirect.trusted-proxy-cidrs[0]", "127.0.0.0/8");
        values.put("shortlink.redirect.internal-token", "test-token");
        values.put("shortlink.redirect.risk-hash-salt", "binding-test-stable-hash-secret-32-bytes");
        values.putAll(overrides);
        return new Binder(ConfigurationPropertySources.from(new MapPropertySource("test", values)))
                .bind("shortlink.redirect", Bindable.of(RedirectProperties.class))
                .get();
    }
}
