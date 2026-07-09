package com.nageoffer.shortlink.gateway.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "short-link.risk.policy")
public class RiskPolicyProperties {

    private boolean trustedProxyEnabled = true;

    private String notFoundMode = "status";

    private String hashSalt = "";

    private String clockZone = "Asia/Shanghai";

    public boolean isTrustedProxyEnabled() {
        return trustedProxyEnabled;
    }

    public void setTrustedProxyEnabled(boolean trustedProxyEnabled) {
        this.trustedProxyEnabled = trustedProxyEnabled;
    }

    public String getNotFoundMode() {
        return notFoundMode;
    }

    public void setNotFoundMode(String notFoundMode) {
        this.notFoundMode = notFoundMode;
    }

    public String getHashSalt() {
        return hashSalt;
    }

    public void setHashSalt(String hashSalt) {
        this.hashSalt = hashSalt;
    }

    public String getClockZone() {
        return clockZone;
    }

    public void setClockZone(String clockZone) {
        this.clockZone = clockZone;
    }
}
