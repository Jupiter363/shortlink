package com.jupiter.shortlink.contract;

import java.io.Serializable;

/** No raw IP, visitor cookie, UA or referrer is retained in derived storage. */
public record EnrichedRecord(
        String kind,
        String clusterId,
        String topicId,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        long receivedAt,
        String timestampType,
        String eventId,
        String payloadHash,
        String tenantId,
        long linkId,
        long occurredAt,
        String visitorHash,
        String ipHash,
        String browser,
        String os,
        String device,
        String country,
        String refererDomain,
        String requestSource,
        String decisionStage,
        int status,
        String reason,
        String validationVersion,
        String validationResult,
        String detailDatasetVersion,
        String parserVersion,
        String hashVersion,
        String province,
        String city,
        String network,
        String geoStatus,
        String geoVersion)
        implements Serializable {
    public EnrichedRecord {
        kind = kind == null ? "" : kind;
        clusterId = clusterId == null ? "" : clusterId;
        topicId = topicId == null ? "" : topicId;
        sourceTopic = sourceTopic == null ? "" : sourceTopic;
        timestampType = timestampType == null ? "" : timestampType;
        eventId = eventId == null ? "" : eventId;
        payloadHash = payloadHash == null ? "" : payloadHash;
        tenantId = tenantId == null ? "" : tenantId;
        visitorHash = visitorHash == null ? "" : visitorHash;
        ipHash = ipHash == null ? "" : ipHash;
        browser = browser == null ? "" : browser;
        os = os == null ? "" : os;
        device = device == null ? "" : device;
        country = country == null ? "" : country;
        refererDomain = refererDomain == null ? "" : refererDomain;
        requestSource = requestSource == null ? "" : requestSource;
        decisionStage = decisionStage == null ? "" : decisionStage;
        reason = reason == null ? "" : reason;
        validationVersion = validationVersion == null ? "" : validationVersion;
        validationResult = validationResult == null ? "" : validationResult;
        detailDatasetVersion = detailDatasetVersion == null ? "" : detailDatasetVersion;
        parserVersion = parserVersion == null ? "" : parserVersion;
        hashVersion = hashVersion == null ? "" : hashVersion;
        province = unknownIfMissing(province);
        city = unknownIfMissing(city);
        network = unknownIfMissing(network);
        geoStatus = unknownIfMissing(geoStatus);
        geoVersion = geoVersion == null ? "" : geoVersion;
    }

    /** Compatibility constructor for frozen pre-geo interpretations. */
    public EnrichedRecord(String kind, String clusterId, String topicId, String sourceTopic,
                          int sourcePartition, long sourceOffset, long receivedAt, String timestampType,
                          String eventId, String payloadHash, String tenantId, long linkId, long occurredAt,
                          String visitorHash, String ipHash, String browser, String os, String device,
                          String country, String refererDomain, String requestSource, String decisionStage,
                          int status, String reason, String validationVersion, String validationResult,
                          String detailDatasetVersion, String parserVersion, String hashVersion) {
        this(kind, clusterId, topicId, sourceTopic, sourcePartition, sourceOffset, receivedAt, timestampType,
                eventId, payloadHash, tenantId, linkId, occurredAt, visitorHash, ipHash, browser, os, device,
                country, refererDomain, requestSource, decisionStage, status, reason, validationVersion,
                validationResult, detailDatasetVersion, parserVersion, hashVersion,
                "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", "");
    }

    private static String unknownIfMissing(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    /** Copies only dimension evidence after the replay caller verifies the frozen fact identity. */
    public EnrichedRecord withGeoFrom(EnrichedRecord enriched) {
        return new EnrichedRecord(kind, clusterId, topicId, sourceTopic, sourcePartition, sourceOffset,
                receivedAt, timestampType, eventId, payloadHash, tenantId, linkId, occurredAt, visitorHash,
                ipHash, browser, os, device, enriched.country(), refererDomain, requestSource, decisionStage,
                status, reason, validationVersion, validationResult, detailDatasetVersion, parserVersion,
                hashVersion, enriched.province(), enriched.city(), enriched.network(), enriched.geoStatus(),
                enriched.geoVersion());
    }

    public boolean valid() {
        return "VALID".equals(validationResult);
    }

    public boolean click() {
        return "CLICK".equals(kind);
    }
}
