package com.nageoffer.shortlink.gateway.risk;

public class RiskPolicyRedisKeyBuilder {

    public String disableShortLinkKey(String domain, String shortUri) {
        return "risk:policy:short-link:disable:" + domain + ":" + shortUri;
    }

    public String rateLimitShortLinkKey(String domain, String shortUri) {
        return "risk:policy:short-link:rate-limit:" + domain + ":" + shortUri;
    }

    public String timeWindowShortLinkKey(String domain, String shortUri) {
        return "risk:policy:short-link:time-window:" + domain + ":" + shortUri;
    }

    public String blockShortLinkIpKey(String domain, String shortUri, String ipHash) {
        return "risk:policy:short-link:block-ip:" + domain + ":" + shortUri + ":" + ipHash;
    }

    public String blockIpKey(String ipHash) {
        return "risk:policy:ip:block:" + ipHash;
    }

    public String rateCounterKey(String domain, String shortUri, String ipHash) {
        return "risk:rate:" + domain + ":" + shortUri + ":" + ipHash;
    }
}
