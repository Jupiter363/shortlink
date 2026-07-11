package com.nageoffer.shortlink.agent.riskcommon.redis;

import org.springframework.util.StringUtils;

public class RiskPolicyRedisKeyBuilder {

    private final String keyPrefix;

    public RiskPolicyRedisKeyBuilder(String keyPrefix) {
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : "risk";
    }

    public String disableShortLinkKey(String domain, String shortUri) {
        return keyPrefix + ":policy:short-link:disable:" + domain + ":" + shortUri;
    }

    public String rateLimitShortLinkKey(String domain, String shortUri) {
        return keyPrefix + ":policy:short-link:rate-limit:" + domain + ":" + shortUri;
    }

    public String timeWindowShortLinkKey(String domain, String shortUri) {
        return keyPrefix + ":policy:short-link:time-window:" + domain + ":" + shortUri;
    }

    public String blockIpKey(String ipHash) {
        return keyPrefix + ":policy:ip:block:" + ipHash;
    }

    public String blockShortLinkIpKey(String domain, String shortUri, String ipHash) {
        return keyPrefix + ":policy:short-link:block-ip:"
                + domain + ":" + shortUri + ":" + ipHash;
    }

    public String rateCounterKey(String domain, String shortUri, String ipHash) {
        return keyPrefix + ":rate:" + domain + ":" + shortUri + ":" + ipHash;
    }
}
