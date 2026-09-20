package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable report publication boundary. The publisher remains responsible for building the manifest. */
public interface ReportLifecycleStore {
    String READY = "READY";

    enum Mode { HISTORY_VIEW, EXPORT }

    record Key(String reportId, int revision) {
        public Key {
            if (reportId == null || reportId.isBlank() || revision < 1)
                throw new IllegalArgumentException("REPORT_REF_INVALID");
        }
    }

    /** retainedUntil controls data readability; reuseExpiresAt is intentionally independent metadata. */
    record Draft(Key key, String runId, int planRevision, String owner,
                 String capability, String manifestJson, String manifestChecksum,
                 Instant evidenceRetainedUntil, Instant retainedUntil, Instant reuseExpiresAt,
                 String payloadJson) {
        public Draft {
            Objects.requireNonNull(key);
            require(runId, "REPORT_RUN_INVALID");
            require(owner, "REPORT_OWNER_INVALID");
            require(capability, "REPORT_CAPABILITY_INVALID");
            require(manifestJson, "REPORT_MANIFEST_INVALID");
            require(manifestChecksum, "REPORT_MANIFEST_CHECKSUM_INVALID");
            require(payloadJson, "REPORT_PAYLOAD_INVALID");
            if (planRevision < 1 || evidenceRetainedUntil == null || retainedUntil == null || reuseExpiresAt == null)
                throw new IllegalArgumentException("REPORT_EXPIRY_INVALID");
            if (!retainedUntil.isBefore(evidenceRetainedUntil) && !retainedUntil.equals(evidenceRetainedUntil))
                throw new IllegalArgumentException("REPORT_RETENTION_EXCEEDS_EVIDENCE");
            if (!manifestChecksum.matches("[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException("REPORT_MANIFEST_CHECKSUM_INVALID");
        }
    }

    record Published(Key key, String runId, int planRevision, String owner, String capability,
                     String manifestJson, String manifestChecksum, Instant evidenceRetainedUntil, Instant retainedUntil,
                     Instant reuseExpiresAt, String payloadJson, long version, int referenceCount) { }

    /** Publishes one immutable revision. Callers must have already assembled and verified evidence. */
    Published publish(Draft draft);

    Optional<Published> read(Key key, String currentOwner, String capability, Mode mode);

    /** Adds a durable consumer reference and returns the new row version. */
    long retain(Key key, String referenceId, String currentOwner, String capability);

    /** Removes one reference. Releasing an absent reference is idempotent. */
    long release(Key key, String referenceId);

    /** Compare-and-delete; returns false when the version changed or references remain. */
    boolean cleanup(Key key, long expectedVersion);

    static void require(String value, String error) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(error);
    }
}
