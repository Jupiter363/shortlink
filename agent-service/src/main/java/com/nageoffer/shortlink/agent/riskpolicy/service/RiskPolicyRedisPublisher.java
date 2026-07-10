package com.nageoffer.shortlink.agent.riskpolicy.service;

import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class RiskPolicyRedisPublisher {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate stringRedisTemplate;
    private final Clock clock;

    @Autowired
    public RiskPolicyRedisPublisher(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, Clock.system(SHANGHAI));
    }

    public RiskPolicyRedisPublisher(StringRedisTemplate stringRedisTemplate, Clock clock) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.clock = clock;
    }

    public boolean publish(RiskPolicy policy) {
        if (policy.expireTime() == null) {
            stringRedisTemplate.opsForValue().set(policy.policyKey(), policy.policyPayloadJson());
            return true;
        }

        Duration ttl = Duration.between(LocalDateTime.now(clock), policy.expireTime());
        if (!ttl.isPositive()) {
            return false;
        }
        stringRedisTemplate.opsForValue().set(policy.policyKey(), policy.policyPayloadJson(), ttl);
        return true;
    }

    public void revoke(RiskPolicy policy) {
        stringRedisTemplate.delete(policy.policyKey());
    }
}
