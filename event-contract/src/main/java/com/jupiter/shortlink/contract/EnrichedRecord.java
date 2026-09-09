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
        String hashVersion)
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
    }

    public boolean valid() {
        return "VALID".equals(validationResult);
    }

    public boolean click() {
        return "CLICK".equals(kind);
    }
}
