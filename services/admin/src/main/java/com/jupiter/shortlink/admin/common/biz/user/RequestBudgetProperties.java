package com.jupiter.shortlink.admin.common.biz.user;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Shared, finite budgets for every admitted Admin request, including internal tools. */
@Component
@ConfigurationProperties(prefix = "shortlink.admin.ingress")
public class RequestBudgetProperties implements InitializingBean {
    private int maxInFlight = 64;
    private int ordinaryBodyBytes = 256 * 1024;
    private int batchBodyBytes = 8 * 1024 * 1024;
    private int totalBodyBytes = 64 * 1024 * 1024;
    private Duration bodyReadTimeout = Duration.ofSeconds(5);

    @Override public void afterPropertiesSet() { validate(); }

    public void validate() {
        if (maxInFlight < 1 || maxInFlight > 65536)
            throw new IllegalArgumentException("Admin max-in-flight must be between 1 and 65536");
        if (ordinaryBodyBytes < 1 || ordinaryBodyBytes > 16 * 1024 * 1024)
            throw new IllegalArgumentException("Admin ordinary-body-bytes must be between 1 and 16 MiB");
        if (batchBodyBytes < ordinaryBodyBytes || batchBodyBytes > 64 * 1024 * 1024)
            throw new IllegalArgumentException("Admin batch-body-bytes must cover ordinary bodies and be at most 64 MiB");
        if (totalBodyBytes < batchBodyBytes || totalBodyBytes > 1024 * 1024 * 1024)
            throw new IllegalArgumentException("Admin total-body-bytes must cover one batch and be at most 1 GiB");
        if (bodyReadTimeout == null || bodyReadTimeout.compareTo(Duration.ofMillis(1)) < 0
                || bodyReadTimeout.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("Admin body-read-timeout must be between 1 ms and 1 minute");
    }

    public int getMaxInFlight() { return maxInFlight; }
    public void setMaxInFlight(int value) { maxInFlight = value; }
    public int getOrdinaryBodyBytes() { return ordinaryBodyBytes; }
    public void setOrdinaryBodyBytes(int value) { ordinaryBodyBytes = value; }
    public int getBatchBodyBytes() { return batchBodyBytes; }
    public void setBatchBodyBytes(int value) { batchBodyBytes = value; }
    public int getTotalBodyBytes() { return totalBodyBytes; }
    public void setTotalBodyBytes(int value) { totalBodyBytes = value; }
    public Duration getBodyReadTimeout() { return bodyReadTimeout; }
    public void setBodyReadTimeout(Duration value) { bodyReadTimeout = value; }
}
