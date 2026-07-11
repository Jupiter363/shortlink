package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentPendingActionService;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.redis.RiskPolicyRedisKeyBuilder;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class RiskActionProposalFactory {

    private static final String AGENT_TYPE = "security-risk";
    private static final String TARGET_TYPE = "SHORT_LINK";
    private static final int PAYLOAD_VERSION = 1;
    private static final int MAX_GID_LENGTH = 64;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_DOMAIN_LENGTH = 256;
    private static final int MAX_SESSION_ID_LENGTH = 256;
    private static final int MAX_KEY_LENGTH = 512;
    private static final Set<RiskReasonCode> STRONG_DISABLE_REASONS =
            Collections.unmodifiableSet(EnumSet.of(
                    RiskReasonCode.TRAFFIC_SPIKE,
                    RiskReasonCode.IP_CONCENTRATION,
                    RiskReasonCode.HIGH_REPEAT_VISIT,
                    RiskReasonCode.PEAK_HOUR_BURST
            ));

    private final AgentProperties properties;

    private final AgentPendingActionService pendingActionService;

    private final RiskPolicyActionPayloadValidator payloadValidator;

    private final RiskIpEvidenceExtractor ipEvidenceExtractor;

    private final JdbcEffectiveRiskPolicyRepository effectiveRepository;

    private final RiskPolicyRedisKeyBuilder policyKeyBuilder;

    private final Clock clock;

    @Autowired
    public RiskActionProposalFactory(
            AgentProperties properties,
            AgentPendingActionService pendingActionService,
            RiskPolicyActionPayloadValidator payloadValidator,
            RiskIpEvidenceExtractor ipEvidenceExtractor,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.pendingActionService = Objects.requireNonNull(
                pendingActionService,
                "pendingActionService must not be null"
        );
        this.payloadValidator = Objects.requireNonNull(
                payloadValidator,
                "payloadValidator must not be null"
        );
        this.ipEvidenceExtractor = Objects.requireNonNull(
                ipEvidenceExtractor,
                "ipEvidenceExtractor must not be null"
        );
        this.effectiveRepository = Objects.requireNonNull(
                effectiveRepository,
                "effectiveRepository must not be null"
        );
        this.policyKeyBuilder = new RiskPolicyRedisKeyBuilder(
                properties.getRisk().getRedis().getKeyPrefix()
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public List<AgentActionProposal> create(RiskActionProposalContext context) {
        if (!validContext(context)) {
            return List.of();
        }
        if (context.directive().isPresent()) {
            return createInteractive(context, context.directive().get())
                    .map(List::of)
                    .orElseGet(List::of);
        }
        if (!context.sourceId().startsWith("risk-profile:")) {
            return List.of();
        }
        return createBatchDisable(context);
    }

    private List<AgentActionProposal> createBatchDisable(RiskActionProposalContext context) {
        AgentProperties.Risk risk = properties.getRisk();
        AgentProperties.Profile profileProperties = risk == null ? null : risk.getProfile();
        AgentProperties.ManualAction manualAction = risk == null ? null : risk.getManualAction();
        if (profileProperties == null || manualAction == null) {
            return List.of();
        }
        int limit = profileProperties.getTopCandidateSize();
        int minimumScore = manualAction.getDisableMinScore();
        int minimumStrongReasons = manualAction.getDisableMinStrongReasonCount();
        if (limit <= 0
                || minimumScore < 0
                || minimumScore > 100
                || minimumStrongReasons <= 0
                || minimumStrongReasons > STRONG_DISABLE_REASONS.size()) {
            return List.of();
        }

        List<AgentActionProposal> proposals = new ArrayList<>(Math.min(
                limit,
                context.shortLinkProfiles().size()
        ));
        for (ShortLinkRiskProfile profile : context.shortLinkProfiles()) {
            if (proposals.size() >= limit) {
                break;
            }
            if (!isBatchDisableCandidate(
                    context,
                    profile,
                    minimumScore,
                    minimumStrongReasons
            )) {
                continue;
            }
            eventId(context, profile)
                    .flatMap(eventId -> createProposal(
                            context,
                            profile,
                            eventId,
                            RiskPolicyAction.DISABLE_SHORT_LINK,
                            null,
                            Optional.empty(),
                            true
                    ))
                    .ifPresent(proposals::add);
        }
        return proposals.isEmpty() ? List.of() : List.copyOf(proposals);
    }

    private Optional<AgentActionProposal> createInteractive(
            RiskActionProposalContext context,
            RiskManualActionDirective directive
    ) {
        if (directive == null || actionType(directive.action()) == null) {
            return Optional.empty();
        }
        for (ShortLinkRiskProfile profile : context.shortLinkProfiles()) {
            if (!completeProfileTarget(context, profile)) {
                continue;
            }
            Optional<String> eventId = eventId(context, profile);
            if (eventId.isEmpty()) {
                continue;
            }
            Optional<RiskIpEvidence> ipEvidence = directive.action() == RiskPolicyAction.BLOCK_IP
                    ? ipEvidenceExtractor.extract(
                            context.toolExecutions(),
                            profile.fullShortUrl()
                    )
                    : Optional.empty();
            if (directive.action() == RiskPolicyAction.BLOCK_IP && ipEvidence.isEmpty()) {
                continue;
            }
            Optional<AgentActionProposal> proposal = createProposal(
                    context,
                    profile,
                    eventId.get(),
                    directive.action(),
                    directive,
                    ipEvidence,
                    false
            );
            if (proposal.isPresent()) {
                return proposal;
            }
        }
        return Optional.empty();
    }

    private Optional<AgentActionProposal> createProposal(
            RiskActionProposalContext context,
            ShortLinkRiskProfile profile,
            String eventId,
            RiskPolicyAction action,
            RiskManualActionDirective directive,
            Optional<RiskIpEvidence> ipEvidence,
            boolean batch
    ) {
        AgentActionType actionType = actionType(action);
        if (actionType == null || !hasText(eventId)) {
            return Optional.empty();
        }
        String targetKey = actionTargetKey(profile, action, ipEvidence);
        if (!hasText(targetKey) || targetKey.length() > MAX_KEY_LENGTH) {
            return Optional.empty();
        }

        RiskPolicyActionPayloadV1 payload;
        try {
            payload = payloadValidator.validate(new RiskPolicyActionPayloadV1(
                    action,
                    profile.gid(),
                    profile.domain(),
                    profile.shortUri(),
                    ipEvidence.map(RiskIpEvidence::ipHash).orElse(null),
                    directive == null ? null : directive.timezone(),
                    directive == null ? List.of() : directive.allowedWindows(),
                    proposalReason(profile, action, batch),
                    eventId,
                    profile.batchId(),
                    null
            ));
        } catch (AgentActionException ignored) {
            return Optional.empty();
        }

        String idempotencyKey = idempotencyKey(
                context,
                profile,
                eventId,
                actionType,
                batch
        );
        if (!hasText(idempotencyKey)) {
            return Optional.empty();
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            return Optional.empty();
        }
        if (pendingActionService.isSuppressed(actionType, targetKey)) {
            return Optional.empty();
        }
        if (hasActiveEffectivePolicy(payload)) {
            return Optional.empty();
        }
        Map<String, Object> targetRef = targetRef(profile, ipEvidence);
        Map<String, Object> evidence = evidence(profile, eventId, ipEvidence);
        return Optional.of(new AgentActionProposal(
                actionId(actionType, idempotencyKey),
                AGENT_TYPE,
                actionType,
                PAYLOAD_VERSION,
                AgentActionAuthorizationScope.GID,
                "",
                profile.gid(),
                TARGET_TYPE,
                targetKey,
                targetRef,
                title(action),
                proposalReason(profile, action, batch),
                payload.toSafeMap(),
                evidence,
                idempotencyKey,
                actionType.value() + ":" + profile.gid() + ":" + targetKey,
                context.proposedBy(),
                context.traceId(),
                eventId,
                profile.batchId(),
                context.sessionId()
        ));
    }

    private boolean isBatchDisableCandidate(
            RiskActionProposalContext context,
            ShortLinkRiskProfile profile,
            int minimumScore,
            int minimumStrongReasons
    ) {
        if (!completeProfileTarget(context, profile)
                || !hasText(profile.batchId())
                || !context.sourceId().equals("risk-profile:" + profile.batchId())
                || profile.riskLevel() != RiskLevel.HIGH
                || profile.riskScore() < minimumScore) {
            return false;
        }
        long strongReasonCount = profile.reasonCodes().stream()
                .filter(STRONG_DISABLE_REASONS::contains)
                .count();
        return strongReasonCount >= minimumStrongReasons;
    }

    private boolean completeProfileTarget(
            RiskActionProposalContext context,
            ShortLinkRiskProfile profile
    ) {
        return profile != null
                && hasText(profile.gid())
                && profile.gid().equals(context.gid())
                && hasText(profile.domain())
                && hasText(profile.shortUri())
                && hasText(profile.fullShortUrl())
                && profile.gid().length() <= MAX_GID_LENGTH
                && profile.domain().length() <= MAX_DOMAIN_LENGTH
                && profile.shortUri().length() <= MAX_IDENTIFIER_LENGTH
                && profile.batchId().length() <= MAX_IDENTIFIER_LENGTH;
    }

    private Optional<String> eventId(
            RiskActionProposalContext context,
            ShortLinkRiskProfile profile
    ) {
        String eventId = context.eventIdsByTarget().get(targetKey(profile));
        return hasText(eventId) && eventId.length() <= MAX_IDENTIFIER_LENGTH
                ? Optional.of(eventId)
                : Optional.empty();
    }

    private String actionTargetKey(
            ShortLinkRiskProfile profile,
            RiskPolicyAction action,
            Optional<RiskIpEvidence> ipEvidence
    ) {
        String shortLinkTarget = targetKey(profile);
        if (action != RiskPolicyAction.BLOCK_IP) {
            return shortLinkTarget;
        }
        return ipEvidence.map(value -> shortLinkTarget + "#" + value.ipHash()).orElse("");
    }

    private String idempotencyKey(
            RiskActionProposalContext context,
            ShortLinkRiskProfile profile,
            String eventId,
            AgentActionType actionType,
            boolean batch
    ) {
        if (!batch) {
            return "risk:" + eventId + ":" + actionType.value();
        }
        if (!hasText(profile.batchId())) {
            return "";
        }
        return String.join(
                ":",
                "risk",
                profile.batchId(),
                context.gid(),
                profile.domain(),
                profile.shortUri(),
                actionType.value()
        );
    }

    private Map<String, Object> targetRef(
            ShortLinkRiskProfile profile,
            Optional<RiskIpEvidence> ipEvidence
    ) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("domain", profile.domain());
        target.put("shortUri", profile.shortUri());
        ipEvidence.ifPresent(value -> target.put("maskedIp", value.maskedIp()));
        return target;
    }

    private Map<String, Object> evidence(
            ShortLinkRiskProfile profile,
            String eventId,
            Optional<RiskIpEvidence> ipEvidence
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("riskScore", profile.riskScore());
        evidence.put("riskLevel", profile.riskLevel().name());
        evidence.put("reasonCodes", profile.reasonCodes().stream()
                .map(RiskReasonCode::name)
                .sorted()
                .toList());
        evidence.put("eventId", eventId);
        if (hasText(profile.batchId())) {
            evidence.put("batchId", profile.batchId());
        }
        ipEvidence.ifPresent(value -> {
            evidence.put("maskedIp", value.maskedIp());
            evidence.put("ipCount", value.count());
        });
        return evidence;
    }

    private AgentActionType actionType(RiskPolicyAction action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case DISABLE_SHORT_LINK -> RiskPolicyActionTypes.DISABLE_SHORT_LINK;
            case LIMIT_TIME_WINDOW -> RiskPolicyActionTypes.LIMIT_TIME_WINDOW;
            case BLOCK_IP -> RiskPolicyActionTypes.BLOCK_IP;
            default -> null;
        };
    }

    private boolean hasActiveEffectivePolicy(RiskPolicyActionPayloadV1 payload) {
        String policyKey = effectivePolicyKey(payload);
        if (!hasText(policyKey)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return effectiveRepository.findByPolicyKey(policyKey)
                .filter(policy -> policy.desiredState() == RiskPolicyDesiredState.ACTIVE)
                .filter(policy -> policy.expireTime() == null || policy.expireTime().isAfter(now))
                .isPresent();
    }

    private String effectivePolicyKey(RiskPolicyActionPayloadV1 payload) {
        return switch (payload.action()) {
            case DISABLE_SHORT_LINK -> policyKeyBuilder.disableShortLinkKey(
                    payload.domain(),
                    payload.shortUri()
            );
            case LIMIT_TIME_WINDOW -> policyKeyBuilder.timeWindowShortLinkKey(
                    payload.domain(),
                    payload.shortUri()
            );
            case BLOCK_IP -> policyKeyBuilder.blockShortLinkIpKey(
                    payload.domain(),
                    payload.shortUri(),
                    payload.ipHash()
            );
            case LIMIT_RATE -> "";
        };
    }

    private String actionId(AgentActionType actionType, String idempotencyKey) {
        String source = actionType.value() + "|" + idempotencyKey;
        return "action-risk-" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private String title(RiskPolicyAction action) {
        return switch (action) {
            case DISABLE_SHORT_LINK -> "Disable high-risk short link";
            case LIMIT_TIME_WINDOW -> "Restrict short-link access window";
            case BLOCK_IP -> "Block suspicious IP for short link";
            default -> "Review security risk action";
        };
    }

    private String proposalReason(
            ShortLinkRiskProfile profile,
            RiskPolicyAction action,
            boolean batch
    ) {
        String source = batch ? "High-confidence batch profile" : "Explicit manual directive";
        return source + " recommends " + action.name() + " for risk score " + profile.riskScore();
    }

    private String targetKey(ShortLinkRiskProfile profile) {
        return profile.domain() + "/" + profile.shortUri();
    }

    private boolean validContext(RiskActionProposalContext context) {
        return context != null
                && hasText(context.sourceId())
                && hasText(context.gid())
                && hasText(context.proposedBy())
                && context.gid().length() <= MAX_GID_LENGTH
                && context.proposedBy().length() <= MAX_IDENTIFIER_LENGTH
                && context.traceId().length() <= MAX_IDENTIFIER_LENGTH
                && context.sessionId().length() <= MAX_SESSION_ID_LENGTH;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
