package com.jupiter.shortlink.redirect.membership;

import com.google.common.hash.Funnel;
import com.google.common.hash.PrimitiveSink;

import java.nio.charset.StandardCharsets;

/** Snapshot format 1: length-prefixed UTF-8, normalized domain, case-sensitive short code. */
enum RouteAddressFunnel implements Funnel<RouteBloomKey> {
    INSTANCE;

    @Override
    public void funnel(RouteBloomKey address, PrimitiveSink into) {
        byte[] domain = address.domainNorm().getBytes(StandardCharsets.UTF_8);
        byte[] uri = address.shortUri().getBytes(StandardCharsets.UTF_8);
        into.putInt(1).putInt(domain.length).putBytes(domain).putInt(uri.length).putBytes(uri);
    }
}
