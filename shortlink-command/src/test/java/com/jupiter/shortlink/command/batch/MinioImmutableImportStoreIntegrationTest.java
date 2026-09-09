package com.jupiter.shortlink.command.batch;

import static org.junit.jupiter.api.Assertions.*;

import io.minio.*;
import io.minio.messages.VersioningConfiguration;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class MinioImmutableImportStoreIntegrationTest {
    private MinioClient client;
    private MinioImmutableImportStore store;
    private String bucket;
    private final List<String> versions = new ArrayList<>();
    private final String key = "imports/1/input.ndjson";

    @BeforeEach
    void open() throws Exception {
        String endpoint = System.getenv("SHORTLINK_BATCH_TEST_MINIO_ENDPOINT");
        assertTrue(endpoint != null, "Isolated MinIO required");
        if (!endpoint.equals("http://127.0.0.1:19000")
                || !"true".equals(System.getenv("SHORTLINK_BATCH_TEST_ALLOW_RESET")))
            throw new IllegalArgumentException(
                    "Dedicated MinIO test endpoint and explicit permission required");
        String access = System.getenv("SHORTLINK_BATCH_TEST_MINIO_ACCESS"),
                secret = System.getenv("SHORTLINK_BATCH_TEST_MINIO_SECRET");
        client = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
        bucket = "shortlink-batch-it-" + UUID.randomUUID();
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        client.setBucketVersioning(
                SetBucketVersioningArgs.builder()
                        .bucket(bucket)
                        .config(
                                new VersioningConfiguration(
                                        VersioningConfiguration.Status.ENABLED, null))
                        .build());
        store =
                new MinioImmutableImportStore(
                        endpoint,
                        access,
                        secret,
                        bucket,
                        new BatchLimits(
                                100000, 8, 2, 134217728, 67108864, 100000, 200, 2, 30000, 3));
    }

    @AfterEach
    void close() throws Exception {
        if (store != null) store.close();
        if (client == null || bucket == null) return;
        for (String version : versions)
            client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .versionId(version)
                            .build());
        client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
    }

    private String put(byte[] bytes) throws Exception {
        String version =
                client.putObject(
                                PutObjectArgs.builder().bucket(bucket).object(key).stream(
                                                new ByteArrayInputStream(bytes), bytes.length, -1)
                                        .build())
                        .versionId();
        versions.add(version);
        return version;
    }

    @Test
    void overwriteCannotChangeAnAdmittedVersion() throws Exception {
        byte[] original = "original-version".getBytes(StandardCharsets.UTF_8);
        String version = put(original);
        put("new-version".getBytes(StandardCharsets.UTF_8));
        var ref =
                new ImmutableImportStore.Reference(
                        bucket, key, version, ImportParser.sha256(original), original.length);
        store.verifyReference(1, ref);
        try (InputStream input = store.open(ref)) {
            assertArrayEquals(original, input.readAllBytes());
        }
        assertThrows(IllegalArgumentException.class, () -> store.verifyReference(2, ref));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.verifyReference(
                                1,
                                new ImmutableImportStore.Reference(
                                        bucket, key, "null", ref.sha256(), ref.bytes())));
    }

    @Test
    void admissionTreatsDeclaredChecksumAsUnvalidatedInput() throws Exception {
        byte[] original = "actual-object".getBytes(StandardCharsets.UTF_8);
        String version = put(original);
        var ref =
                new ImmutableImportStore.Reference(
                        bucket, key, version, "0".repeat(64), original.length + 1);
        store.verifyReference(1, ref);
        try (InputStream input = store.open(ref)) {
            byte[] actual = input.readAllBytes();
            assertNotEquals(ref.sha256(), ImportParser.sha256(actual));
            assertNotEquals(ref.bytes(), actual.length);
        }
    }
}
