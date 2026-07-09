package com.nageoffer.shortlink.agent.riskpolicy.service;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.redis.RiskPolicyRedisKeyBuilder;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskHashService;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyActivationCommand;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDisableCommand;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

@Service
public class RiskPolicyService {

    private static final Set<RiskReasonCode> STRONG_LIMIT_RATE_REASONS = EnumSet.of(
            RiskReasonCode.TRAFFIC_SPIKE,
            RiskReasonCode.IP_CONCENTRATION,
            RiskReasonCode.HIGH_REPEAT_VISIT,
            RiskReasonCode.PEAK_HOUR_BURST
    );

    private final JdbcRiskPolicyRepository policyRepository;
    private final JdbcRiskActionAuditRepository auditRepository;
    private final RiskPolicyRedisPublisher redisPublisher;
    private final AgentProperties properties;
    private final RiskPolicyRedisKeyBuilder keyBuilder;

    public RiskPolicyService(
            JdbcRiskPolicyRepository policyRepository,
            JdbcRiskActionAuditRepository auditRepository,
            RiskPolicyRedisPublisher redisPublisher,
            AgentProperties properties
    ) {
        this.policyRepository = policyRepository;
        this.auditRepository = auditRepository;
        this.redisPublisher = redisPublisher;
        this.properties = properties;
        this.keyBuilder = new RiskPolicyRedisKeyBuilder(properties.getRisk().getRedis().getKeyPrefix());
    }

    public RiskPolicy activatePolicy(RiskPolicyActivationCommand command) {
        RiskPolicy policy = toPolicy(command);
        policyRepository.saveActive(policy);
        if (!redisPublisher.publish(policy)) {
            RiskPolicy expiredPolicy = policy.withStatus(RiskPolicyStatus.EXPIRED);
            policyRepository.expire(policy.policyId(), command.traceId());
            auditRepository.saveActivationAudit(expiredPolicy, command.executor(), command.reason());
            return expiredPolicy;
        }
        auditRepository.saveActivationAudit(policy, command.executor(), command.reason());
        return policy;
    }

    public void disablePolicy(RiskPolicyDisableCommand command) {
        RiskPolicy policy = policyRepository.findByPolicyId(command.policyId())
                .orElseThrow(() -> new IllegalArgumentException("Risk policy not found: " + command.policyId()));
        if (command.gid() == null || !command.gid().equals(policy.gid())) {
            throw new IllegalArgumentException("Risk policy is not owned by gid: " + command.gid());
        }
        policyRepository.disable(command.policyId(), command.traceId());
        redisPublisher.revoke(policy);
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

    private RiskPolicy toPolicy(RiskPolicyActivationCommand command) {
        if (command.action() == RiskPolicyAction.BLOCK_IP) {
            String ipHash = new RiskHashService(properties.getRisk().getHashSalt()).sha256(command.rawIp());
            return RiskPolicy.ipPolicy(
                    command.policyId(),
                    keyBuilder.blockIpKey(ipHash),
                    command.gid(),
                    ipHash,
                    command.policyPayloadJson(),
                    command.source(),
                    command.traceId(),
                    command.eventId(),
                    command.expireTime()
            );
        }

        String policyKey = shortLinkPolicyKey(command.action(), command.domain(), command.shortUri());
        return RiskPolicy.shortLinkPolicy(
                command.policyId(),
                policyKey,
                command.action(),
                command.gid(),
                command.domain(),
                command.shortUri(),
                command.policyPayloadJson(),
                command.source(),
                command.traceId(),
                command.eventId(),
                command.expireTime()
        );
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
