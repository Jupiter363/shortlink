package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RiskPolicyExpiryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskPolicyExpiryService.class);

    private final JdbcEffectiveRiskPolicyRepository effectiveRepository;
    private final JdbcRiskPolicyRepository policyRepository;
    private final JdbcRiskPolicySyncOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;

    public RiskPolicyExpiryService(
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            JdbcRiskPolicyRepository policyRepository,
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.effectiveRepository = Objects.requireNonNull(effectiveRepository, "effectiveRepository must not be null");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
    }

    public boolean expireNext(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");
        List<EffectiveRiskPolicy> due = effectiveRepository.findDueActive(now, 1);
        if (due.isEmpty()) {
            return false;
        }
        EffectiveRiskPolicy candidate = due.get(0);
        Boolean expired = expireCandidateInTransaction(candidate, now);
        return Boolean.TRUE.equals(expired);
    }

    public int expireBatch(LocalDateTime now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        List<EffectiveRiskPolicy> due = effectiveRepository.findDueActive(now, Math.max(1, limit));
        int expiredCount = 0;
        for (EffectiveRiskPolicy candidate : due) {
            try {
                if (Boolean.TRUE.equals(expireCandidateInTransaction(candidate, now))) {
                    expiredCount++;
                }
            } catch (RuntimeException ex) {
                LOGGER.warn(
                        "Risk policy expiry candidate failed: {}",
                        ex.getClass().getSimpleName()
                );
            }
        }
        return expiredCount;
    }

    private Boolean expireCandidateInTransaction(
            EffectiveRiskPolicy candidate,
            LocalDateTime now
    ) {
        return transactionTemplate.execute(status -> expireCandidate(candidate, now));
    }

    private boolean expireCandidate(EffectiveRiskPolicy candidate, LocalDateTime now) {
        EffectiveRiskPolicy current = effectiveRepository
                .findByPolicyKeyForUpdate(candidate.policyKey())
                .orElse(null);
        if (!isSameDuePolicy(current, candidate, now)) {
            return false;
        }
        String outboxId = outboxId(
                current.policyKey(),
                current.policyVersion(),
                RiskPolicySyncOperation.DELETE
        );
        if (!policyRepository.markExpired(current.policyId(), current.traceId())) {
            throw new IllegalStateException("Risk policy history changed during expiration");
        }
        outboxRepository.createIfAbsent(new RiskPolicySyncOutbox(
                null,
                outboxId,
                current.policyKey(),
                current.policyId(),
                current.policyVersion(),
                RiskPolicySyncOperation.DELETE,
                "",
                current.redisValueJson(),
                RiskPolicySyncOutboxStatus.PENDING,
                0,
                null,
                "",
                null,
                "",
                now,
                now
        ));
        if (!effectiveRepository.markExpiredIfVersion(
                current.policyKey(),
                current.policyId(),
                current.policyVersion(),
                outboxId
        )) {
            throw new IllegalStateException("Risk policy effective slot changed during expiration");
        }
        return true;
    }

    private boolean isSameDuePolicy(
            EffectiveRiskPolicy current,
            EffectiveRiskPolicy candidate,
            LocalDateTime now
    ) {
        return current != null
                && current.policyId().equals(candidate.policyId())
                && current.policyVersion() == candidate.policyVersion()
                && current.desiredState() == RiskPolicyDesiredState.ACTIVE
                && current.expireTime() != null
                && !current.expireTime().isAfter(now);
    }

    private String outboxId(
            String policyKey,
            long policyVersion,
            RiskPolicySyncOperation operation
    ) {
        String value = operation.name() + "|" + policyKey + "|" + policyVersion;
        return "outbox-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
