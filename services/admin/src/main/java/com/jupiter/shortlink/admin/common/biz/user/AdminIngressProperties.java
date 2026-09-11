package com.jupiter.shortlink.admin.common.biz.user;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.List;

/** Empty peer and host lists deliberately fail closed. */
@ConfigurationProperties(prefix = "shortlink.admin.ingress")
public class AdminIngressProperties {
    private List<String> allowedHosts = List.of(), trustedProxyCidrs = List.of();
    private Duration sessionTimeout = Duration.ofMillis(150), redisConnectTimeout = Duration.ofSeconds(1);
    private int redisRequestQueueSize = 256;
    public List<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(List<String> value) { allowedHosts = List.copyOf(value); }
    public List<String> getTrustedProxyCidrs() { return trustedProxyCidrs; }
    public void setTrustedProxyCidrs(List<String> value) { trustedProxyCidrs = List.copyOf(value); }
    public Duration getSessionTimeout() { return sessionTimeout; }
    public void setSessionTimeout(Duration value) { sessionTimeout = bounded(value); }
    public Duration getRedisConnectTimeout() { return redisConnectTimeout; }
    public void setRedisConnectTimeout(Duration value) { redisConnectTimeout = bounded(value); }
    public int getRedisRequestQueueSize() { return redisRequestQueueSize; }
    public void setRedisRequestQueueSize(int value) {
        if (value < 1 || value > 4096) throw new IllegalArgumentException("Redis request queue must be between 1 and 4096");
        redisRequestQueueSize = value;
    }
    private static Duration bounded(Duration value) {
        if (value == null || value.toMillis() < 1 || value.compareTo(Duration.ofSeconds(2)) > 0)
            throw new IllegalArgumentException("Ingress Redis timeout must be between 1ms and 2s");
        return value;
    }
}
