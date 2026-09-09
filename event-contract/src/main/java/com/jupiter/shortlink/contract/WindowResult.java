package com.jupiter.shortlink.contract;

import java.io.Serializable;

public record WindowResult(
        String tenantId,
        long linkId,
        long windowStart,
        long windowEnd,
        int bucket,
        long revision,
        long pv,
        double uv,
        double uip,
        String visitorSketch,
        String ipSketch,
        String buildId,
        String recoveryEpoch,
        String metricVersion,
        String detailDatasetVersion)
        implements Serializable {}
