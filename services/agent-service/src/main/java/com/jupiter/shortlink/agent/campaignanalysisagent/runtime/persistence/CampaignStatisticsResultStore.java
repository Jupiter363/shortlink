package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import java.util.Optional;

/** Backend-only staged pages. A receipt is not readable evidence until its manifest is published. */
public interface CampaignStatisticsResultStore {
    String ARTIFACT_TYPE = "StatisticsJobPages";
    String SCHEMA_VERSION = "statistics-job-pages/v1";

    /** Immutable backend binding; expiresAtMillis cannot exceed the original remote result expiry. */
    record ReceiptSpec(String jobId, String requestHash, String artifactId, String scopeRef,
                       String periodsRef, long totalRows, int pageCount, long expiresAtMillis) {}

    record Receipt(ReceiptSpec spec, int nextPageIndex, int storedPages, long storedRows,
                   String snapshotJson, String metricsJson, String chainHash, boolean published) {
        public int requiredPages() { return Math.max(1, spec.pageCount()); }
        public boolean complete() { return storedPages == requiredPages() && storedRows == spec.totalRows(); }
    }

    /** Canonical page payload retains all data; snapshot excludes page-local cursor fields. */
    record Page(int pageIndex, Integer nextPageIndex, int rowCount, String snapshotJson,
                String metricsJson, String payloadJson) {}

    Optional<Receipt> receipt(RunToken token, String childId);
    Receipt initialize(DispatchPermit permit, ReceiptSpec spec);
    /** Payload + checksum + next index commit together, with exact attempt fencing. */
    Receipt append(DispatchPermit permit, Page page);
    /** Small manifest + READY child commit together; never aggregates all page payloads in memory. */
    ArtifactRef publish(DispatchPermit permit);
    /** Every read repeats Artifact authorization/expiry checks; staging cannot be read through this API. */
    String readPage(Caller current, String artifactId, int pageIndex, ArtifactAuthorizer authorizer);
}
