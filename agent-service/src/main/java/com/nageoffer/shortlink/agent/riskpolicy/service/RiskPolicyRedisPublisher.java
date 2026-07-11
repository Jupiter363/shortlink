package com.nageoffer.shortlink.agent.riskpolicy.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class RiskPolicyRedisPublisher {

    private static final String COMPARE_AND_DELETE_SCRIPT_PATH = "lua/risk_policy_compare_and_delete.lua";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate stringRedisTemplate;
    private final Clock clock;
    private final DefaultRedisScript<Long> compareAndDeleteScript;

    @Autowired
    public RiskPolicyRedisPublisher(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, Clock.system(SHANGHAI));
    }

    public RiskPolicyRedisPublisher(StringRedisTemplate stringRedisTemplate, Clock clock) {
        this(stringRedisTemplate, clock, new ClassPathResource(COMPARE_AND_DELETE_SCRIPT_PATH));
    }

    public RiskPolicyRedisPublisher(
            StringRedisTemplate stringRedisTemplate,
            Clock clock,
            Resource compareAndDeleteScriptResource
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.clock = clock;
        this.compareAndDeleteScript = loadCompareAndDeleteScript(compareAndDeleteScriptResource);
    }

    public boolean publish(String policyKey, String redisValueJson, LocalDateTime expireTime) {
        requireText(policyKey, "policyKey");
        requireText(redisValueJson, "redisValueJson");
        if (expireTime == null) {
            stringRedisTemplate.opsForValue().set(policyKey, redisValueJson);
            return true;
        }

        Duration ttl = Duration.between(LocalDateTime.now(clock), expireTime);
        if (!ttl.isPositive()) {
            return false;
        }
        stringRedisTemplate.opsForValue().set(policyKey, redisValueJson, ttl);
        return true;
    }

    public RiskPolicyDeleteResult compareAndDelete(String policyKey, String expectedRedisValue) {
        requireText(policyKey, "policyKey");
        requireText(expectedRedisValue, "expectedRedisValue");
        Long result = stringRedisTemplate.execute(
                compareAndDeleteScript,
                List.of(policyKey),
                expectedRedisValue
        );
        if (Long.valueOf(1L).equals(result)) {
            return RiskPolicyDeleteResult.DELETED;
        }
        if (Long.valueOf(0L).equals(result)) {
            return RiskPolicyDeleteResult.ALREADY_ABSENT;
        }
        if (Long.valueOf(-1L).equals(result)) {
            return RiskPolicyDeleteResult.VALUE_MISMATCH;
        }
        throw new IllegalStateException("Unexpected risk policy delete result");
    }

    private DefaultRedisScript<Long> loadCompareAndDeleteScript(Resource resource) {
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException("Risk policy compare-and-delete script is required");
        }
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptSource(new ResourceScriptSource(resource));
            script.setResultType(Long.class);
            script.getScriptAsString();
            return script;
        } catch (Exception ex) {
            throw new IllegalStateException("Risk policy compare-and-delete script is required", ex);
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
