package com.jupiter.shortlink.command.batch;

import java.io.IOException;
import java.io.InputStream;

/** Version is an object-store version ID, never an ETag or a mutable key alias. */
public interface ImmutableImportStore {
    record Reference(String bucket, String key, String version, String sha256, long bytes) {}

    void verifyReference(long tenantId, Reference reference);

    InputStream open(Reference reference) throws IOException;
}
