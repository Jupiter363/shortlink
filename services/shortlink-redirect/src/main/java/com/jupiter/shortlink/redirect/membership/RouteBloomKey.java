package com.jupiter.shortlink.redirect.membership;

/** Validation/normalization already ran at ingress or registry ingestion, outside Bloom hashing. */
record RouteBloomKey(String domainNorm, String shortUri) {}
