package com.nageoffer.shortlink.agent.riskpolicy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.redis.RiskPolicyRedisKeyBuilder;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyActivationCommand;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDisableCommand;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class RiskPolicyService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Set<RiskReasonCode> STRONG_LIMIT_RATE_REASONS = EnumSet.of(
            RiskReasonCode.TRAFFIC_SPIKE,
            RiskReasonCode.IP_CONCENTRATION,
            RiskReasonCode.HIGH_REPEAT_VISIT,
            RiskReasonCode.PEAK_HOUR_BURST
    );

    private final JdbcRiskPolicyRepository policyRepository;
    private final JdbcRiskActionAuditRepository auditRepository;
    private final RiskPolicyRedisPublisher redisPublisher;
    private final RiskPolicyRedisValueCodec redisValueCodec;
    private final AgentProperties properties;
    private final RiskPolicyRedisKeyBuilder keyBuilder;
    private final Clock clock;

    @Autowired
    public RiskPolicyService(
            JdbcRiskPolicyRepository policyRepository,
            JdbcRiskActionAuditRepository auditRepository,
            RiskPolicyRedisPublisher redisPublisher,
            RiskPolicyRedisValueCodec redisValueCodec,
            AgentProperties properties
    ) {
        this(
                policyRepository,
                auditRepository,
                redisPublisher,
                redisValueCodec,
                properties,
                Clock.system(SHANGHAI)
        );
    }

    public RiskPolicyService(
            JdbcRiskPolicyRepository policyRepository,
            JdbcRiskActionAuditRepository auditRepository,
            RiskPolicyRedisPublisher redisPublisher,
            AgentProperties properties,
            Clock clock
    ) {
        this(
                policyRepository,
                auditRepository,
                redisPublisher,
                new RiskPolicyRedisValueCodec(new ObjectMapper()),
                properties,
                clock
        );
    }

    public RiskPolicyService(
            JdbcRiskPolicyRepository policyRepository,
            JdbcRiskActionAuditRepository auditRepository,
            RiskPolicyRedisPublisher redisPublisher,
            RiskPolicyRedisValueCodec redisValueCodec,
            AgentProperties properties,
            Clock clock
    ) {
        this.policyRepository = policyRepository;
        this.auditRepository = auditRepository;
        this.redisPublisher = redisPublisher;
        this.redisValueCodec = redisValueCodec;
        this.properties = properties;
        this.keyBuilder = new RiskPolicyRedisKeyBuilder(properties.getRisk().getRedis().getKeyPrefix());
        this.clock = clock;
    }

    public RiskPolicy activatePolicy(RiskPolicyActivationCommand command) {
        RiskPolicy existing = policyRepository.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
        if (existing != null) {
            requireMatchingIdempotentCommand(existing, command);
            return completeActivation(
                    existing,
                    command,
                    policyRepository.findByPolicyKeyOrderByVersion(existing.policyKey())
            );
        }

        String policyKey = policyKey(command);
        List<RiskPolicy> history = policyRepository.findByPolicyKeyOrderByVersion(policyKey);
        long nextVersion = history.isEmpty() ? 1L : history.get(0).policyVersion() + 1L;
        LocalDateTime effectiveTime = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        RiskPolicy policy = toPolicy(command, policyKey, nextVersion, effectiveTime);
        try {
            policyRepository.insert(policy);
        } catch (DataIntegrityViolationException ex) {
            RiskPolicy concurrent = policyRepository.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
            if (concurrent != null) {
                requireMatchingIdempotentCommand(concurrent, command);
                return completeActivation(
                        concurrent,
                        command,
                        policyRepository.findByPolicyKeyOrderByVersion(concurrent.policyKey())
                );
            }
            throw ex;
        }
        return completeActivation(policy, command, history);
    }

    private RiskPolicy completeActivation(
            RiskPolicy policy,
            RiskPolicyActivationCommand command,
            List<RiskPolicy> history
    ) {
        if (auditRepository.countByPolicyId(policy.policyId()) > 0) {
            return policy;
        }
        if (policy.status() == RiskPolicyStatus.EXPIRED) {
            auditRepository.saveActivationAudit(policy, command.executor(), command.reason());
            return policy;
        }
        if (policy.status() != RiskPolicyStatus.ACTIVE) {
            return policy;
        }
        String redisValue = redisValueCodec.encode(
                policy.policyId(),
                policy.policyVersion(),
                policy.policyPayloadJson()
        );
        if (!redisPublisher.publish(policy.policyKey(), redisValue, policy.expireTime())) {
            RiskPolicy expiredPolicy = policy.withStatus(RiskPolicyStatus.EXPIRED);
            policyRepository.markExpired(policy.policyId(), command.traceId());
            auditRepository.saveActivationAudit(expiredPolicy, command.executor(), command.reason());
            return expiredPolicy;
        }
        history.stream()
                .filter(value -> value.status() == RiskPolicyStatus.ACTIVE)
                .filter(value -> !value.policyId().equals(policy.policyId()))
                .forEach(value -> policyRepository.markSuperseded(value.policyId(), command.traceId()));
        auditRepository.saveActivationAudit(policy, command.executor(), command.reason());
        return policy;
    }

    public void disablePolicy(RiskPolicyDisableCommand command) {
        RiskPolicy policy = policyRepository.findByPolicyId(command.policyId())
                .orElseThrow(() -> new IllegalArgumentException("Risk policy not found: " + command.policyId()));
        if (command.gid() == null || !command.gid().equals(policy.gid())) {
            throw new IllegalArgumentException("Risk policy is not owned by gid: " + command.gid());
        }
        if (policy.status() != RiskPolicyStatus.ACTIVE
                || policyRepository.findActiveByPolicyKey(policy.policyKey())
                .map(RiskPolicy::policyId)
                .filter(policy.policyId()::equals)
                .isEmpty()) {
            throw new IllegalStateException("Risk policy is not the active version: " + command.policyId());
        }
        policyRepository.markDisabled(command.policyId(), command.traceId());
        String expectedRedisValue = redisValueCodec.encode(
                policy.policyId(),
                policy.policyVersion(),
                policy.policyPayloadJson()
        );
        redisPublisher.compareAndDelete(policy.policyKey(), expectedRedisValue);
        auditRepository.saveDisableAudit(policy, command.executor(), command.reason());
    }

    public boolean canAutoLimitRate(RiskLevel level, int score, Set<RiskReasonCode> reasonCodes) {
        if (!properties.getRisk().getAutoAction().isLimitRateEnabled()) {
            return false;
        }
        if (level != RiskLevel.HIGH || score < properties.getRisk().getAutoAction().getLimitRateMinScore()) {
            return false;
        }
        long strongReasonCount = reasonCodes.stream()
                .filter(STRONG_LIMIT_RATE_REASONS::contains)
                .count();
        return strongReasonCount >= 2;
    }

    private RiskPolicy toPolicy(
            RiskPolicyActivationCommand command,
            String policyKey,
            long policyVersion,
            LocalDateTime effectiveTime
    ) {
        if (command.action() == RiskPolicyAction.BLOCK_IP) {
            return RiskPolicy.ipPolicy(
                    command.policyId(),
                    policyKey,
                    command.idempotencyKey(),
                    policyVersion,
                    command.gid(),
                    command.domain(),
                    command.shortUri(),
                    command.ipHash(),
                    command.policyPayloadJson(),
                    command.source(),
                    command.traceId(),
                    command.eventId(),
                    effectiveTime,
                    command.expireTime()
            );
        }

        return RiskPolicy.shortLinkPolicy(
                command.policyId(),
                policyKey,
                command.idempotencyKey(),
                policyVersion,
                command.action(),
                command.gid(),
                command.domain(),
                command.shortUri(),
                command.policyPayloadJson(),
                command.source(),
                command.traceId(),
                command.eventId(),
                effectiveTime,
                command.expireTime()
        );
    }

    private String policyKey(RiskPolicyActivationCommand command) {
        if (command.action() == RiskPolicyAction.BLOCK_IP) {
            return keyBuilder.blockIpKey(command.ipHash());
        }
        return shortLinkPolicyKey(command.action(), command.domain(), command.shortUri());
    }

    private void requireMatchingIdempotentCommand(
            RiskPolicy existing,
            RiskPolicyActivationCommand command
    ) {
        boolean matches = Objects.equals(existing.policyId(), command.policyId())
                && Objects.equals(existing.policyKey(), policyKey(command))
                && existing.action() == command.action()
                && Objects.equals(existing.gid(), command.gid())
                && Objects.equals(existing.domain(), command.domain())
                && Objects.equals(existing.shortUri(), command.shortUri())
                && Objects.equals(existing.ipHash(), command.ipHash())
                && Objects.equals(existing.policyPayloadJson(), command.policyPayloadJson())
                && existing.source() == command.source()
                && Objects.equals(existing.expireTime(), command.expireTime());
        if (!matches) {
            throw new IllegalArgumentException("Risk policy idempotency conflict");
        }
    }

    private String shortLinkPolicyKey(RiskPolicyAction action, String domain, String shortUri) {
        return switch (action) {
            case DISABLE_SHORT_LINK -> keyBuilder.disableShortLinkKey(domain, shortUri);
            case LIMIT_RATE -> keyBuilder.rateLimitShortLinkKey(domain, shortUri);
            case LIMIT_TIME_WINDOW -> keyBuilder.timeWindowShortLinkKey(domain, shortUri);
            case BLOCK_IP -> throw new IllegalArgumentException("BLOCK_IP is not a short-link policy");
        };
    }
}
