package com.jupiter.shortlink.analytics.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public record WorkerSettings(
        @Value("${analytics.kafka.bootstrap:localhost:9092}") String bootstrap,
        @Value("${analytics.internal-token}") String internalToken,
        @Value("${analytics.hash-key}") String hashKey,
        @Value("${analytics.s3.endpoint:http://localhost:9000}") String objectEndpoint,
        @Value("${analytics.s3.access-key}") String objectAccessKey,
        @Value("${analytics.s3.secret-key}") String objectSecretKey,
        @Value("${analytics.s3.bucket:shortlink-raw}") String bucket,
        @Value("${analytics.clickhouse.urls:http://localhost:8123}") String clickhouseUrls,
        @Value("${analytics.clickhouse.user:default}") String clickhouseUser,
        @Value("${analytics.clickhouse.password:}") String clickhousePassword,
        @Value("${analytics.clickhouse.database:shortlink_analytics}") String clickhouseDatabase) {
    public WorkerSettings {
        if (internalToken == null || internalToken.length() < 24)
            throw new IllegalArgumentException(
                    "analytics.internal-token must have >=24 characters");
        if (hashKey == null || hashKey.length() < 32)
            throw new IllegalArgumentException("analytics.hash-key must have >=32 characters");
        if (!clickhouseDatabase.matches("[A-Za-z][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Invalid ClickHouse database");
    }

    public List<String> replicas() {
        return List.of(clickhouseUrls.split(","));
    }
}
