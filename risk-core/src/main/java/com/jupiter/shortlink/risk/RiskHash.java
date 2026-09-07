package com.jupiter.shortlink.risk;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Tenant scoped IP identity shared with the versioned analytics detail contract. */
public final class RiskHash {
    public static final String VERSION = "hmac-sha256-128-v1";
    private final SecretKeySpec key;

    public RiskHash(String salt) {
        if (salt == null || salt.isBlank())
            throw new IllegalArgumentException("Risk hash salt is required");
        key = new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String hash(String tenantId, String normalizedIp) {
        if (tenantId == null || !tenantId.matches("[1-9][0-9]{0,18}"))
            throw new IllegalArgumentException("Tenant identity required");
        // Canonicalizes the same numeric IPv4/IPv6 representation emitted by EventEnricher.
        String ip = IpAddresses.normalize(normalizedIp);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            for (String component : new String[] {"ip", tenantId, ip}) {
                byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal(), 0, 16);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Risk hash unavailable", exception);
        }
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
}
