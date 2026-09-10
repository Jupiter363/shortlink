package com.jupiter.shortlink.admin.remote.dto.resp;

/** Immutable receipt fields, deliberately separate from current link metadata. */
public record CommittedCreateExportRow(
        long linkId,
        String fullShortUrl,
        String originUrl,
        String gid,
        String shortUri,
        long routeVersion,
        long targetRevision) {}
