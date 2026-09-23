package com.jupiter.shortlink.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "short-link.agent.deepseek")
public class DeepSeekProperties {

    private String apiKey = "";

    private String baseUrl = "https://api.deepseek.com";

    private String model = "deepseek-flash";

    private int timeoutMs = 30000;

    private int maxOutputTokens = 2000;

    /** Used only by the explicitly selected campaign-plan-v2 model instance. */
    private int campaignPlanMaxOutputTokens = 8192;

    /** Generous transport ceiling, independent of the analysis or answer token budget. */
    private int maxResponseBytes = 16 * 1024 * 1024;

    private int maxResponseNestingDepth = 128;

    /**
     * Graph nodes already compute the evidence before asking for an explanation.
     * Keep the bounded output budget available for the answer instead of relying
     * on the provider's default, which enables high-effort thinking.
     */
    private boolean thinkingEnabled = false;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getCampaignPlanMaxOutputTokens() {
        return campaignPlanMaxOutputTokens;
    }

    public void setCampaignPlanMaxOutputTokens(int campaignPlanMaxOutputTokens) {
        if (campaignPlanMaxOutputTokens < 1)
            throw new IllegalArgumentException("Campaign model output token limit must be positive");
        this.campaignPlanMaxOutputTokens = campaignPlanMaxOutputTokens;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        if (maxResponseBytes < 1) throw new IllegalArgumentException("Model response byte limit must be positive");
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getMaxResponseNestingDepth() {
        return maxResponseNestingDepth;
    }

    public void setMaxResponseNestingDepth(int maxResponseNestingDepth) {
        if (maxResponseNestingDepth < 1) throw new IllegalArgumentException("Model JSON nesting limit must be positive");
        this.maxResponseNestingDepth = maxResponseNestingDepth;
    }

    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }
}
