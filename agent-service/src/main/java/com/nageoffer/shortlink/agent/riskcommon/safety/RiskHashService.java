package com.nageoffer.shortlink.agent.riskcommon.safety;

import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class RiskHashService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String salt;

    public RiskHashService(String salt) {
        this.salt = salt;
    }

    public String sha256(String value) {
        if (!StringUtils.hasText(salt)) {
            throw new IllegalStateException("Risk hash salt must be configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Failed to hash risk value", ex);
        }
    }
}
