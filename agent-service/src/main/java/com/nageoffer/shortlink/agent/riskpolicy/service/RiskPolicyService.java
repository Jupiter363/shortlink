package com.nageoffer.shortlink.agent.riskpolicy.service;

import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskReview;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.redis.RiskPolicyRedisKeyBuilder;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPayloadV1;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPayloadValidator;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPort;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyConfirmedActionCommand;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyConfirmedActionResult;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyActivationCommand;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDisableCommand;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.JdbcRiskPolicySyncOutboxRepository;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOperation;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutbox;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutboxStatus;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class RiskPolicyService implements RiskPolicyActionPort {

    private static final int TRANSACTION_MAX_ATTEMPTS = 3;
    private static final String PAYLOAD_INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String PAYLOAD_INVALID_MESSAGE = "Risk policy action payload is invalid";
    private static final String PAYLOAD_CONFLICT_CODE = "ACTION_PAYLOAD_CONFLICT";
    private static final String PAYLOAD_CONFLICT_MESSAGE =
            "Risk policy action conflicts with an existing policy";
    private static final String POLICY_NOT_EFFECTIVE_CODE = "POLICY_NOT_EFFECTIVE";
    private static final String POLICY_NOT_EFFECTIVE_MESSAGE =
            "Risk policy is not the current effective version";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final RiskPolicyActionPayloadValidator ACTION_PAYLOAD_VALIDATOR =
            new RiskPolicyActionPayloadValidator();
    private static final Set<RiskReasonCode> STRONG_LIMIT_RATE_REASONS = EnumSet.of(
            RiskReasonCode.TRAFFIC_SPIKE,
            RiskReasonCode.IP_CONCENTRATION,
            RiskReasonCode.HIGH_REPEAT_VISIT,
            RiskReasonCode.PEAK_HOUR_BURST
    );

    private final JdbcRiskPolicyRepository policyRepository;
    private final JdbcEffectiveRiskPolicyRepository effectiveRepository;
    private final JdbcRiskActionAuditRepository auditRepository;
    private final JdbcRiskReviewRepository reviewRepository;
    private final JdbcRiskPolicySyncOutboxRepository outboxRepository;
    private final RiskPolicyRedisValueCodec redisValueCodec;
    private final AgentProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final RiskPolicyRedisKeyBuilder keyBuilder;
    private final Clock clock;

    @Autowired
    public RiskPolicyService(
            JdbcRiskPolicyRepository policyRepository,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            JdbcRiskActionAuditRepository auditRepository,
            JdbcRiskReviewRepository reviewRepository,
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            RiskPolicyRedisValueCodec redisValueCodec,
            AgentProperties properties,
            TransactionTemplate transactionTemplate
    ) {
        this(
                policyRepository,
                effectiveRepository,
                auditRepository,
                reviewRepository,
                outboxRepository,
                redisValueCodec,
                properties,
                transactionTemplate,
                Clock.system(SHANGHAI)
        );
    }

    public RiskPolicyService(
            JdbcRiskPolicyRepository policyRepository,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            JdbcRiskActionAuditRepository auditRepository,
            JdbcRiskReviewRepository reviewRepository,
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            RiskPolicyRedisValueCodec redisValueCodec,
            AgentProperties properties,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository must not be null");
        this.effectiveRepository = Objects.requireNonNull(effectiveRepository, "effectiveRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.redisValueCodec = Objects.requireNonNull(redisValueCodec, "redisValueCodec must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.keyBuilder = new RiskPolicyRedisKeyBuilder(properties.getRisk().getRedis().getKeyPrefix());
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public RiskPolicy activatePolicy(RiskPolicyActivationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ActivationOutcome outcome = executeWithRetry(
                status -> activateInTransaction(command, null)
        );
        return requireOutcome(outcome).policy();
    }

    @Override
    public RiskPolicyConfirmedActionResult execute(RiskPolicyConfirmedActionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        requireConfirmedIdentity(command);
        RiskPolicyActionPayloadV1 payload = ACTION_PAYLOAD_VALIDATOR.validate(command.payload());
        RiskPolicyActivationCommand activationCommand = confirmedActivationCommand(command, payload);
        ActivationOutcome outcome = executeWithRetry(
                status -> activateInTransaction(activationCommand, command)
        );
        ActivationOutcome completed = requireOutcome(outcome);
        return new RiskPolicyConfirmedActionResult(
                completed.policy().policyId(),
                completed.policy().policyKey(),
                completed.policy().policyVersion(),
                completed.policy().status().name(),
                completed.syncStatus().name()
        );
    }

    public void disablePolicy(RiskPolicyDisableCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        executeWithRetry(status -> {
            disableInTransaction(command);
            return Boolean.TRUE;
        });
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

    private ActivationOutcome activateInTransaction(
            RiskPolicyActivationCommand command,
            RiskPolicyConfirmedActionCommand confirmedCommand
    ) {
        RiskPolicy existing = policyRepository.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
        if (existing != null) {
            requireMatchingIdempotentCommand(existing, command);
            return existingOutcome(existing);
        }

        String policyKey = policyKey(command);
        EffectiveRiskPolicy currentSlot = effectiveRepository
                .findByPolicyKeyForUpdate(policyKey)
                .orElse(null);
        List<RiskPolicy> history = policyRepository.findByPolicyKeyOrderByVersionForUpdate(policyKey);
        long nextVersion = nextVersion(currentSlot, history);
        LocalDateTime now = now();
        RiskPolicy policy = toPolicy(command, policyKey, nextVersion, now);
        if (isExpired(command.expireTime(), now)) {
            RiskPolicy expired = policy.withStatus(RiskPolicyStatus.EXPIRED);
            policyRepository.insert(expired);
            auditRepository.saveActivationAudit(expired, command.executor(), command.reason());
            saveConfirmedReview(confirmedCommand, now);
            return new ActivationOutcome(expired, RiskPolicySyncStatus.SYNCED);
        }

        policyRepository.insert(policy);
        requireCurrentHistoryInvariant(currentSlot, history);
        history.stream()
                .filter(value -> value.status() == RiskPolicyStatus.ACTIVE)
                .forEach(value -> {
                    if (!policyRepository.markSuperseded(value.policyId(), command.traceId())) {
                        throw new IllegalStateException("Risk policy history changed during activation");
                    }
                });

        String redisValue = redisValueCodec.encode(
                policy.policyId(),
                policy.policyVersion(),
                policy.policyPayloadJson()
        );
        String outboxId = outboxId(policyKey, nextVersion, RiskPolicySyncOperation.UPSERT);
        effectiveRepository.upsert(new EffectiveRiskPolicy(
                null,
                policyKey,
                policy.policyId(),
                nextVersion,
                policy.gid(),
                policy.action(),
                RiskPolicyDesiredState.ACTIVE,
                policy.policyPayloadJson(),
                redisValue,
                now,
                policy.expireTime(),
                RiskPolicySyncStatus.PENDING,
                outboxId,
                policy.traceId(),
                now,
                now
        ));
        auditRepository.saveActivationAudit(policy, command.executor(), command.reason());
        saveConfirmedReview(confirmedCommand, now);
        outboxRepository.createIfAbsent(pendingOutbox(
                outboxId,
                policy,
                nextVersion,
                RiskPolicySyncOperation.UPSERT,
                redisValue,
                "",
                now
        ));
        return new ActivationOutcome(policy, RiskPolicySyncStatus.PENDING);
    }

    private void disableInTransaction(RiskPolicyDisableCommand command) {
        RiskPolicy policy = policyRepository.findByPolicyId(command.policyId())
                .orElseThrow(() -> new IllegalArgumentException("Risk policy not found: " + command.policyId()));
        if (command.gid() == null || !command.gid().equals(policy.gid())) {
            throw new IllegalArgumentException("Risk policy is not owned by gid: " + command.gid());
        }
        EffectiveRiskPolicy current = effectiveRepository
                .findByPolicyKeyForUpdate(policy.policyKey())
                .orElseThrow(this::policyNotEffective);
        if (policy.status() != RiskPolicyStatus.ACTIVE
                || current.desiredState() != RiskPolicyDesiredState.ACTIVE
                || !current.policyId().equals(policy.policyId())
                || current.policyVersion() != policy.policyVersion()) {
            throw policyNotEffective();
        }

        LocalDateTime now = now();
        long deleteVersion = current.policyVersion() + 1L;
        String outboxId = outboxId(policy.policyKey(), deleteVersion, RiskPolicySyncOperation.DELETE);
        if (!policyRepository.markDisabled(policy.policyId(), command.traceId())) {
            throw policyNotEffective();
        }
        effectiveRepository.upsert(new EffectiveRiskPolicy(
                current.id(),
                current.policyKey(),
                current.policyId(),
                deleteVersion,
                current.gid(),
                current.action(),
                RiskPolicyDesiredState.DISABLED,
                current.policyPayloadJson(),
                current.redisValueJson(),
                now,
                current.expireTime(),
                RiskPolicySyncStatus.PENDING,
                outboxId,
                command.traceId(),
                current.createTime(),
                now
        ));
        auditRepository.saveDisableAudit(
                policy.withStatus(RiskPolicyStatus.DISABLED),
                command.executor(),
                command.reason(),
                command.traceId()
        );
        outboxRepository.createIfAbsent(pendingOutbox(
                outboxId,
                policy,
                deleteVersion,
                RiskPolicySyncOperation.DELETE,
                "",
                current.redisValueJson(),
                now
        ));
    }

    private RiskPolicyActivationCommand confirmedActivationCommand(
            RiskPolicyConfirmedActionCommand command,
            RiskPolicyActionPayloadV1 payload
    ) {
        String reason = command.confirmationNote().isBlank()
                ? payload.reason()
                : command.confirmationNote();
        return new RiskPolicyActivationCommand(
                command.policyId(),
                command.idempotencyKey(),
                payload.action(),
                payload.gid(),
                payload.domain(),
                payload.shortUri(),
                payload.ipHash(),
                redisValueCodec.encodePayload(payload.toSafeMap()),
                RiskPolicySource.MANUAL_REVIEW,
                command.confirmedBy(),
                reason,
                command.traceId(),
                payload.eventId(),
                payload.expireTime()
        );
    }

    private void requireConfirmedIdentity(RiskPolicyConfirmedActionCommand command) {
        String expectedPolicyId = "policy-action-" + deterministicUuid(command.actionId());
        String expectedIdempotencyKey = "manual:" + command.actionId();
        if (!expectedPolicyId.equals(command.policyId())
                || !expectedIdempotencyKey.equals(command.idempotencyKey())) {
            throw new AgentActionException(PAYLOAD_INVALID_CODE, PAYLOAD_INVALID_MESSAGE);
        }
    }

    private void saveConfirmedReview(
            RiskPolicyConfirmedActionCommand command,
            LocalDateTime now
    ) {
        if (command == null) {
            return;
        }
        RiskPolicyActionPayloadV1 payload = command.payload();
        reviewRepository.saveReview(new RiskReview(
                "review-confirm-" + deterministicUuid("confirm|" + command.actionId()),
                payload.eventId(),
                RiskTargetType.SHORT_LINK,
                payload.gid(),
                payload.domain(),
                payload.shortUri(),
                "",
                RiskReviewAction.CONFIRM_RISK,
                command.confirmedBy(),
                command.confirmationNote(),
                now
        ));
    }

    private ActivationOutcome existingOutcome(RiskPolicy policy) {
        RiskPolicySyncStatus syncStatus = effectiveRepository.findByPolicyKey(policy.policyKey())
                .filter(slot -> slot.policyId().equals(policy.policyId()))
                .filter(slot -> slot.policyVersion() == policy.policyVersion())
                .map(EffectiveRiskPolicy::syncStatus)
                .orElse(policy.status() == RiskPolicyStatus.ACTIVE
                        ? RiskPolicySyncStatus.PENDING
                        : RiskPolicySyncStatus.SYNCED);
        return new ActivationOutcome(policy, syncStatus);
    }

    private RiskPolicySyncOutbox pendingOutbox(
            String outboxId,
            RiskPolicy policy,
            long policyVersion,
            RiskPolicySyncOperation operation,
            String redisValue,
            String expectedRedisValue,
            LocalDateTime now
    ) {
        return new RiskPolicySyncOutbox(
                null,
                outboxId,
                policy.policyKey(),
                policy.policyId(),
                policyVersion,
                operation,
                redisValue,
                expectedRedisValue,
                RiskPolicySyncOutboxStatus.PENDING,
                0,
                null,
                "",
                null,
                "",
                now,
                now
        );
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
            return keyBuilder.blockShortLinkIpKey(
                    command.domain(),
                    command.shortUri(),
                    command.ipHash()
            );
        }
        return switch (command.action()) {
            case DISABLE_SHORT_LINK -> keyBuilder.disableShortLinkKey(command.domain(), command.shortUri());
            case LIMIT_RATE -> keyBuilder.rateLimitShortLinkKey(command.domain(), command.shortUri());
            case LIMIT_TIME_WINDOW -> keyBuilder.timeWindowShortLinkKey(command.domain(), command.shortUri());
            case BLOCK_IP -> throw new IllegalArgumentException("BLOCK_IP is not a short-link policy");
        };
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
            throw new AgentActionException(PAYLOAD_CONFLICT_CODE, PAYLOAD_CONFLICT_MESSAGE);
        }
    }

    private long nextVersion(EffectiveRiskPolicy slot, List<RiskPolicy> history) {
        long slotVersion = slot == null ? 0L : slot.policyVersion();
        long historyVersion = history.isEmpty() ? 0L : history.get(0).policyVersion();
        return Math.max(slotVersion, historyVersion) + 1L;
    }

    private void requireCurrentHistoryInvariant(
            EffectiveRiskPolicy slot,
            List<RiskPolicy> history
    ) {
        if (slot == null || slot.desiredState() != RiskPolicyDesiredState.ACTIVE) {
            return;
        }
        boolean matchingActiveHistory = history.stream().anyMatch(policy ->
                policy.policyId().equals(slot.policyId())
                        && policy.policyVersion() == slot.policyVersion()
                        && policy.status() == RiskPolicyStatus.ACTIVE
        );
        if (!matchingActiveHistory) {
            throw new IllegalStateException("Effective risk policy has no matching active history");
        }
    }

    private String outboxId(
            String policyKey,
            long policyVersion,
            RiskPolicySyncOperation operation
    ) {
        return "outbox-" + deterministicUuid(
                operation.name() + "|" + policyKey + "|" + policyVersion
        );
    }

    private String deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isExpired(LocalDateTime expireTime, LocalDateTime now) {
        return expireTime != null && !expireTime.isAfter(now);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private ActivationOutcome requireOutcome(ActivationOutcome outcome) {
        return Objects.requireNonNull(outcome, "Risk policy transaction returned no result");
    }

    private <T> T executeWithRetry(TransactionCallback<T> callback) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= TRANSACTION_MAX_ATTEMPTS; attempt++) {
            try {
                return Objects.requireNonNull(
                        transactionTemplate.execute(callback),
                        "Risk policy transaction returned no result"
                );
            } catch (TransientDataAccessException | DataIntegrityViolationException ex) {
                lastFailure = ex;
                if (attempt == TRANSACTION_MAX_ATTEMPTS) {
                    throw ex;
                }
                Thread.onSpinWait();
            }
        }
        throw Objects.requireNonNull(lastFailure, "Risk policy transaction failed");
    }

    private AgentActionException policyNotEffective() {
        return new AgentActionException(POLICY_NOT_EFFECTIVE_CODE, POLICY_NOT_EFFECTIVE_MESSAGE);
    }

    private record ActivationOutcome(
            RiskPolicy policy,
            RiskPolicySyncStatus syncStatus
    ) {
    }
}
