package com.nageoffer.shortlink.agent.riskpolicy.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;

import java.util.ArrayList;
import java.util.List;

public class RiskPolicyPayload {

    private RiskPolicyAction action;

    private Integer limit;

    private Integer windowSeconds;

    private String timezone;

    private List<String> allowedWindows = new ArrayList<>();

    private List<String> blockedWindows = new ArrayList<>();

    private String reason;

    public RiskPolicyAction getAction() {
        return action;
    }

    public void setAction(RiskPolicyAction action) {
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
}
