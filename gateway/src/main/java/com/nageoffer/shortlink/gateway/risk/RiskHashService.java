package com.nageoffer.shortlink.gateway.risk;

import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class RiskHashService {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final String salt;

    public RiskHashService(String salt) {
        this.salt = salt;
    }

    public String sha256(String value) {
        if (!StringUtils.hasText(salt)) {
            throw new IllegalStateException("Risk hash salt is required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            byte[] digest = mac.doFinal(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Risk hash calculation failed", ex);
        }
    }

    private String toHex(byte[] digest) {
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }
}
