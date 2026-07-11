package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.riskcommon.safety.RiskIpSafety;

import java.util.regex.Pattern;

public record RiskIpEvidence(String ipHash, String maskedIp, long count) {

    private static final String INVALID_MESSAGE = "Risk IP evidence is invalid";
    private static final Pattern IP_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public RiskIpEvidence {
        if (ipHash == null
                || !IP_HASH_PATTERN.matcher(ipHash).matches()
                || !RiskIpSafety.isAllowedMaskedIp(maskedIp)
                || count <= 0) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        maskedIp = maskedIp.trim();
    }

    @Override
    public String toString() {
        return "RiskIpEvidence[ipHash=" + ipHash + ", maskedIp=" + maskedIp + ", count=" + count + "]";
    }
}
