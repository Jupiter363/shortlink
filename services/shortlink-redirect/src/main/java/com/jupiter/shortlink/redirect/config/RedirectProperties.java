package com.jupiter.shortlink.redirect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "shortlink.redirect")
public record RedirectProperties(
        String instanceId,
        List<String> allowedHosts,
        List<String> trustedProxyCidrs,
        String internalToken,
        String riskHashSalt,
        @DefaultValue("http://127.0.0.1:8001") String commandBaseUrl,
        @DefaultValue("1000") long authorityTtlMillis,
        @DefaultValue("500") long requestTimeoutMillis,
        @DefaultValue("100") long redisTimeoutMillis,
        @DefaultValue("100000") int cacheEntries,
        @DefaultValue("16") int originConcurrency,
        @DefaultValue("200") int clusterOriginRate,
        @DefaultValue("1000") int kafkaQueueCapacity,
        @DefaultValue("8388608") long kafkaQueueBytes,
        @DefaultValue("16384") int eventMaxBytes,
        @DefaultValue("127.0.0.1:9092") String kafkaBootstrap,
        @DefaultValue("16") int clickBuckets,
        @DefaultValue("1000") int generationPollMillis) {
    public RedirectProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Unique instanceId required");
        if (internalToken == null
                || internalToken.isBlank()
                || riskHashSalt == null
                || riskHashSalt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException(
                    "Internal token and stable analytics/risk hash key of at least 32 bytes"
                            + " required");
        if (allowedHosts.isEmpty() || trustedProxyCidrs.isEmpty())
            throw new IllegalArgumentException("Allowed hosts and trusted proxy CIDRs required");
        if (authorityTtlMillis < 1
                || authorityTtlMillis > 1000
                || requestTimeoutMillis < 1
                || requestTimeoutMillis >= authorityTtlMillis
                || redisTimeoutMillis < 1
                || cacheEntries < 1
                || originConcurrency < 1
                || clusterOriginRate < 1
                || kafkaQueueCapacity < 1
                || kafkaQueueBytes < eventMaxBytes
                || eventMaxBytes < 1024
                || clickBuckets < 1
                || generationPollMillis < 1)
            throw new IllegalArgumentException("Invalid bounded resource configuration");
    }
}
