package com.nageoffer.shortlink.gateway.risk;

import java.util.ArrayList;
import java.util.List;

public class RiskPolicyPayload {

    private String action;

    private Integer limit;

    private Integer windowSeconds;

    private String timezone;

    private List<String> allowedWindows = new ArrayList<>();

    private List<String> blockedWindows = new ArrayList<>();

    private String reason;

    private Long expireEpochMs;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(Integer windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public List<String> getAllowedWindows() {
        return allowedWindows;
    }

    public void setAllowedWindows(List<String> allowedWindows) {
        this.allowedWindows = allowedWindows;
    }

    public List<String> getBlockedWindows() {
        return blockedWindows;
    }

    public void setBlockedWindows(List<String> blockedWindows) {
        this.blockedWindows = blockedWindows;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getExpireEpochMs() {
        return expireEpochMs;
    }

    public void setExpireEpochMs(Long expireEpochMs) {
        this.expireEpochMs = expireEpochMs;
    }
}
