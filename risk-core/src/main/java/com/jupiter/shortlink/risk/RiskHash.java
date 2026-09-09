package com.jupiter.shortlink.risk;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Tenant scoped IP identity shared with the versioned analytics detail contract. */
public final class RiskHash {
    public static final String VERSION = "hmac-sha256-128-v1";
    private final SecretKeySpec key;
    private final ThreadLocal<Mac> threadMac;

    public RiskHash(String salt) {
        this(salt, () -> Mac.getInstance("HmacSHA256"));
    }

    RiskHash(String salt, MacFactory macFactory) {
        if (salt == null || salt.isBlank())
            throw new IllegalArgumentException("Risk hash salt is required");
        key = new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Objects.requireNonNull(macFactory, "Mac factory is required");
        // A long-lived RiskHash uses one keyed Mac per calling pool thread, never per tenant/IP.
        // Keep initialization lazy so constructor and invalid-input failures remain unchanged.
        threadMac = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = macFactory.create();
                mac.init(key);
                return mac;
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("Risk hash unavailable", exception);
            }
        });
    }

    public String hash(String tenantId, String normalizedIp) {
        if (!validTenant(tenantId))
            throw new IllegalArgumentException("Tenant identity required");
        // Canonicalizes the same numeric IPv4/IPv6 representation emitted by EventEnricher.
        String ip = IpAddresses.normalize(normalizedIp);
        // All three components are ASCII after validation. Preserve the v1 UTF-8 bytes and
        // big-endian length prefixes with one bounded payload instead of per-component buffers.
        byte[] payload = new byte[14 + tenantId.length() + ip.length()];
        int offset = writeComponent(payload, 0, "ip");
        offset = writeComponent(payload, offset, tenantId);
        writeComponent(payload, offset, ip);
        try {
            // Successful doFinal resets Mac to its keyed initial state for the next call.
            return HexFormat.of().formatHex(threadMac.get().doFinal(payload), 0, 16);
        } catch (RuntimeException | Error failure) {
            // A provider may fail after updating its mutable state. Never reuse that state.
            threadMac.remove();
            throw failure;
        }
    }

    private static boolean validTenant(String tenantId) {
        if (tenantId == null || tenantId.isEmpty() || tenantId.length() > 19
                || tenantId.charAt(0) < '1' || tenantId.charAt(0) > '9') return false;
        for (int i = 1; i < tenantId.length(); i++) {
            char ch = tenantId.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private static int writeComponent(byte[] payload, int offset, String value) {
        int length = value.length();
        payload[offset++] = (byte) (length >>> 24);
        payload[offset++] = (byte) (length >>> 16);
        payload[offset++] = (byte) (length >>> 8);
        payload[offset++] = (byte) length;
        for (int i = 0; i < length; i++) payload[offset++] = (byte) value.charAt(i);
        return offset;
    }

    /** Legacy migration helper only. Production callers must supply the tenant identity. */
    @Deprecated(forRemoval = true)
    public String hash(String normalizedIp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of()
                    .formatHex(mac.doFinal(normalizedIp.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Risk hash unavailable", exception);
        }
    }

    @FunctionalInterface
    interface MacFactory {
        Mac create() throws GeneralSecurityException;
    }
}
