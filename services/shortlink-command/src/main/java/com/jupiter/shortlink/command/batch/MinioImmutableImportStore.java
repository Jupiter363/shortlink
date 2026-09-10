package com.jupiter.shortlink.command.batch;

import io.minio.*;

import okhttp3.OkHttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

@Component
public class MinioImmutableImportStore implements ImmutableImportStore, AutoCloseable {
    private final MinioClient client;
    private final MinioClient statClient;
    private final OkHttpClient http;
    private final java.util.concurrent.Semaphore admissionSlots =
            new java.util.concurrent.Semaphore(16);
    private final String bucket;
    private final BatchLimits limits;

    public MinioImmutableImportStore(
            @Value("${shortlink.batch.object.endpoint}") String endpoint,
            @Value("${shortlink.batch.object.access-key}") String access,
            @Value("${shortlink.batch.object.secret-key}") String secret,
            @Value("${shortlink.batch.object.bucket}") String bucket,
            BatchLimits limits) {
        this.bucket = bucket;
        this.limits = limits;
        this.http =
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .readTimeout(Duration.ofSeconds(10))
                        .writeTimeout(Duration.ofSeconds(10))
                        .callTimeout(Duration.ofMinutes(5))
                        .retryOnConnectionFailure(false)
                        .build();
        this.client =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(access, secret)
                        .httpClient(http)
                        .build();
        this.statClient =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(access, secret)
                        .httpClient(http.newBuilder().callTimeout(Duration.ofSeconds(5)).build())
                        .build();
    }

    @Override
    public void verifyReference(long tenant, Reference ref) {
        if (ref == null
                || !bucket.equals(ref.bucket())
                || ref.key() == null
                || !ref.key().startsWith("imports/" + tenant + "/")
                || ref.key().length() > 512
                || ref.key().contains("..")
                || ref.key().chars().anyMatch(c -> c < 32)
                || ref.version() == null
                || ref.version().isBlank()
                || ref.version().equals("null")
                || ref.version().length() > 256
                || ref.sha256() == null
                || !ref.sha256().matches("[0-9a-f]{64}")
                || ref.bytes() < 1
                || ref.bytes() > limits.maxInputBytes())
            throw new IllegalArgumentException(
                    "A controlled, completed, explicitly versioned import object is required");
        if (!admissionSlots.tryAcquire())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Import reference-check capacity exhausted");
        try {
            StatObjectResponse stat =
                    statClient.statObject(
                            StatObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(ref.key())
                                    .versionId(ref.version())
                                    .build());
            if (!ref.version().equals(stat.versionId()) || stat.size() > limits.maxInputBytes())
                throw new IllegalArgumentException("Object version or bounded size is invalid");
            // Declared checksum/bytes remain untrusted until VALIDATING reads the complete version.
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Immutable import version unavailable", e);
        } finally {
            admissionSlots.release();
        }
    }

    @Override
    public InputStream open(Reference ref) throws IOException {
        try {
            GetObjectResponse response =
                    client.getObject(
                            GetObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(ref.key())
                                    .versionId(ref.version())
                                    .build());
            if (!ref.version().equals(response.headers().get("x-amz-version-id"))) {
                response.close();
                throw new IOException("Object store did not confirm the fixed version");
            }
            return response;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot open immutable import", e);
        }
    }

    @Override
    @jakarta.annotation.PreDestroy
    public void close() {
        http.dispatcher().cancelAll();
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }
}
