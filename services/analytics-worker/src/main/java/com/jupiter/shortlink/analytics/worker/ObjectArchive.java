package com.jupiter.shortlink.analytics.worker;

import io.minio.*;

import org.springframework.stereotype.Component;

import java.io.*;

@Component
public class ObjectArchive {
    private final MinioClient client;
    private final String bucket;

    public ObjectArchive(WorkerSettings s) {
        client =
                MinioClient.builder()
                        .endpoint(s.objectEndpoint())
                        .credentials(s.objectAccessKey(), s.objectSecretKey())
                        .httpClient(
                                new okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(java.time.Duration.ofSeconds(3))
                                        .readTimeout(java.time.Duration.ofSeconds(15))
                                        .callTimeout(java.time.Duration.ofSeconds(60))
                                        .build())
                        .build();
        bucket = s.bucket();
    }

    public void put(String key, byte[] bytes) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
                throw new IllegalStateException("Provision archive bucket before starting worker");
            client.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(key).stream(
                                    new ByteArrayInputStream(bytes), bytes.length, -1)
                            .contentType("application/x-ndjson")
                            .build());
        } catch (Exception e) {
            throw new IllegalStateException("Archive persistence failed", e);
        }
    }

    public InputStream open(String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new IllegalStateException("Archive unavailable", e);
        }
    }

    public void list(String prefix, java.util.function.Consumer<String> each) {
        try {
            for (var r :
                    client.listObjects(
                            ListObjectsArgs.builder()
                                    .bucket(bucket)
                                    .prefix(prefix)
                                    .recursive(true)
                                    .build())) {
                var item = r.get();
                if (!item.isDir()) each.accept(item.objectName());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Archive catalog unavailable", e);
        }
    }

    public java.util.List<String> page(String prefix, String after, int limit) {
        try {
            var builder =
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .recursive(true)
                            .maxKeys(Math.min(1000, limit));
            if (after != null && !after.isBlank()) builder.startAfter(after);
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (var r : client.listObjects(builder.build())) {
                var item = r.get();
                if (!item.isDir()) keys.add(item.objectName());
                if (keys.size() >= limit) break;
            }
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException("Archive catalog page unavailable", e);
        }
    }

    public void establishEpoch(String epoch) {
        byte[] bytes = epoch.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        put("recovery/epochs/" + epoch, bytes);
        put("recovery/active-epoch", bytes);
    }

    public String activeEpoch() {
        try (var in = open("recovery/active-epoch")) {
            String value = new String(in.readNBytes(128), java.nio.charset.StandardCharsets.UTF_8);
            if (!value.matches("[0-9a-f-]{36}"))
                throw new IllegalStateException("Invalid external recovery epoch");
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Recovery authority unavailable", e);
        }
    }
}
