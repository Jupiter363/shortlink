package com.nageoffer.shortlink.agent.harness.action.service;

import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutor;
import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutorRegistry;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionClaim;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionContext;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionPage;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposalResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.harness.action.repository.JdbcAgentPendingActionRepository;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class AgentPendingActionService {

    private static final String PAYLOAD_INVALID = "ACTION_PAYLOAD_INVALID";
    private static final String NOT_FOUND = "ACTION_NOT_FOUND";
    private static final String EXECUTOR_UNAVAILABLE = "ACTION_EXECUTOR_UNAVAILABLE";
    private static final String NOT_CONFIRMABLE = "ACTION_NOT_CONFIRMABLE";
    private static final String VERSION_CONFLICT = "ACTION_VERSION_CONFLICT";
    private static final String EXECUTION_FAILED = "ACTION_EXECUTION_FAILED";
    private static final String REVIEW_ACTION_INVALID = "ACTION_REVIEW_ACTION_INVALID";

    private static final String PAYLOAD_INVALID_MESSAGE = "Agent action request is invalid";
    private static final String NOT_FOUND_MESSAGE = "Agent action was not found";
    private static final String EXECUTOR_UNAVAILABLE_MESSAGE =
            "Agent action executor is unavailable";
    private static final String NOT_CONFIRMABLE_MESSAGE = "Agent action cannot be confirmed";
    private static final String VERSION_CONFLICT_MESSAGE = "Agent action version has changed";
    private static final String EXECUTION_FAILED_MESSAGE = "Agent action execution failed";
    private static final String REVIEW_ACTION_INVALID_MESSAGE =
            "Agent action review action is invalid";
    private static final String ENRICHMENT_FAILED_MESSAGE = "Agent action view enrichment failed";
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 2048;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcAgentPendingActionRepository repository;
    private final AgentActionPayloadCodec codec;
    private final AgentActionExecutorRegistry registry;
    private final AgentActionAuthorizationService authorizationService;
    private final AgentProperties properties;
    private final List<AgentActionViewEnricher> enrichers;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;

    @Autowired
    public AgentPendingActionService(
            JdbcAgentPendingActionRepository repository,
            AgentActionPayloadCodec codec,
            AgentActionExecutorRegistry registry,
            AgentActionAuthorizationService authorizationService,
            AgentProperties properties,
            List<AgentActionViewEnricher> enrichers
    ) {
        this(
                repository,
                codec,
                registry,
                authorizationService,
                properties,
                enrichers,
                Clock.system(DEFAULT_ZONE),
                AgentPendingActionService::newExecutionToken
        );
    }

    public AgentPendingActionService(
            JdbcAgentPendingActionRepository repository,
            AgentActionPayloadCodec codec,
            AgentActionExecutorRegistry registry,
            AgentActionAuthorizationService authorizationService,
            AgentProperties properties,
            List<AgentActionViewEnricher> enrichers,
            Clock clock,
            Supplier<String> tokenSupplier
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.authorizationService = Objects.requireNonNull(
                authorizationService,
                "authorizationService must not be null"
        );
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.enrichers = enrichers == null ? List.of() : List.copyOf(enrichers);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
    }

    public AgentPendingActionView propose(AgentActionProposal proposal, Duration ttl) {
        LocalDateTime now = now();
        LocalDateTime expireTime;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw error(PAYLOAD_INVALID, PAYLOAD_INVALID_MESSAGE);
        }
        try {
            expireTime = now.plus(ttl);
        } catch (DateTimeException | ArithmeticException ex) {
            throw error(PAYLOAD_INVALID, PAYLOAD_INVALID_MESSAGE);
        }
        if (!expireTime.isAfter(now)) {
            throw error(PAYLOAD_INVALID, PAYLOAD_INVALID_MESSAGE);
        }
        AgentActionProposalResult result = repository.propose(proposal, codec, now, expireTime);
        return toView(result.action());
    }

    public AgentPendingActionView detail(String actionId, AgentActionActor actor) {
        AgentPendingAction action = load(actionId);
        authorizationService.authorize(action, actor);
        return toView(action);
    }

    public AgentActionPage<AgentPendingActionView> page(
            String gid,
            String agentType,
            String actionType,
            AgentActionStatus status,
            int pageNo,
            int pageSize,
            AgentActionActor actor
    ) {
        authorizationService.authorizePage(gid, actor);
        AgentActionPage<AgentPendingAction> page = repository.page(
                gid,
                agentType,
                actionType,
                status,
                pageNo,
                pageSize
        );
        List<AgentPendingActionView> views = new ArrayList<>(page.records().size());
        for (AgentPendingAction action : page.records()) {
            authorizationService.authorize(action, actor);
            views.add(toView(action));
        }
        return new AgentActionPage<>(views, page.total(), page.pageNo(), page.pageSize());
    }

    public AgentPendingActionView confirm(
            String actionId,
            long expectedVersion,
            AgentActionActor actor,
            String note
    ) {
        AgentPendingAction current = load(actionId);
        authorizationService.authorize(current, actor);
        if (current.status() == AgentActionStatus.EXECUTED
                || current.status() == AgentActionStatus.EXECUTING) {
            return toView(current);
        }
        if (!isConfirmable(current, now())) {
            throw error(NOT_CONFIRMABLE, NOT_CONFIRMABLE_MESSAGE);
        }

        AgentActionExecutor executor = registry.findByType(current.actionType())
                .orElseThrow(() -> error(
                        EXECUTOR_UNAVAILABLE,
                        EXECUTOR_UNAVAILABLE_MESSAGE
                ));
        String executionToken = tokenSupplier.get();
        if (!hasText(executionToken)) {
            throw error(EXECUTION_FAILED, EXECUTION_FAILED_MESSAGE);
        }
        LocalDateTime claimTime = now();
        Duration lease = executionLease();
        String confirmedBy = actor == null ? null : actor.username();
        Optional<AgentActionClaim> claimed = repository.claimForExecution(
                actionId,
                expectedVersion,
                executionToken,
                claimTime,
                lease,
                executor.replaySafe(),
                confirmedBy
        );
        if (claimed.isEmpty()) {
            return resolveClaimMiss(
                    actionId,
                    expectedVersion,
                    actor,
                    executor.replaySafe()
            );
        }
        return executeClaim(claimed.get(), executor, actor, note);
    }

    public AgentPendingActionView reject(
            String actionId,
            long expectedVersion,
            AgentActionActor actor,
            String reason,
            String reviewAction
    ) {
        AgentPendingAction current = load(actionId);
        authorizationService.authorize(current, actor);
        LocalDateTime now = now();
        if (!isRejectable(current, now)) {
            throw error(NOT_CONFIRMABLE, NOT_CONFIRMABLE_MESSAGE);
        }
        if (reason == null) {
            throw error(PAYLOAD_INVALID, PAYLOAD_INVALID_MESSAGE);
        }
        String normalizedReviewAction = normalizeReviewAction(reviewAction);
        String rejectedBy = actor == null ? null : actor.username();
        if (repository.reject(
                actionId,
                expectedVersion,
                rejectedBy,
                reason,
                normalizedReviewAction,
                now
        )) {
            AgentPendingAction rejected = load(actionId);
            authorizationService.authorize(rejected, actor);
            return toView(rejected);
        }

        AgentPendingAction latest = load(actionId);
        authorizationService.authorize(latest, actor);
        if (!isRejectable(latest, now())) {
            throw error(NOT_CONFIRMABLE, NOT_CONFIRMABLE_MESSAGE);
        }
        throw error(VERSION_CONFLICT, VERSION_CONFLICT_MESSAGE);
    }

    private AgentPendingActionView executeClaim(
            AgentActionClaim claim,
            AgentActionExecutor executor,
            AgentActionActor actor,
            String note
    ) {
        String resultJson;
        try {
            AgentActionExecutionResult executionResult = executor.execute(
                    claim.action(),
                    new AgentActionExecutionContext(actor, note)
            );
            Map<String, Object> result = executionResult == null
                    ? Map.of()
                    : executionResult.result();
            resultJson = codec.canonicalJson(result);
        } catch (RuntimeException ex) {
            throw failClaim(claim, ex);
        }

        boolean completed;
        try {
            completed = repository.completeExecution(
                    claim.action().actionId(),
                    claim.executionToken(),
                    claim.claimedVersion(),
                    resultJson,
                    now()
            );
        } catch (RuntimeException ex) {
            throw failClaim(claim, ex);
        }
        if (!completed) {
            throw failClaim(claim, null);
        }

        AgentPendingAction stored = load(claim.action().actionId());
        authorizationService.authorize(stored, actor);
        return toView(stored);
    }

    private AgentActionException failClaim(AgentActionClaim claim, RuntimeException failure) {
        try {
            repository.failExecution(
                    claim.action().actionId(),
                    claim.executionToken(),
                    claim.claimedVersion(),
                    EXECUTION_FAILED,
                    stableFailureMessage(failure),
                    now()
            );
        } catch (RuntimeException ignored) {
            // The original stable execution error remains authoritative.
        }
        return error(EXECUTION_FAILED, EXECUTION_FAILED_MESSAGE);
    }

    private AgentPendingActionView resolveClaimMiss(
            String actionId,
            long expectedVersion,
            AgentActionActor actor,
            boolean replaySafe
    ) {
        AgentPendingAction latest = load(actionId);
        authorizationService.authorize(latest, actor);
        if (latest.status() == AgentActionStatus.EXECUTED
                || latest.status() == AgentActionStatus.EXECUTING) {
            return toView(latest);
        }
        if (latest.status() == AgentActionStatus.REJECTED
                || latest.status() == AgentActionStatus.EXPIRED
                || isExpired(latest, now())
                || (!replaySafe && latest.status() == AgentActionStatus.FAILED)) {
            throw error(NOT_CONFIRMABLE, NOT_CONFIRMABLE_MESSAGE);
        }
        if ((latest.status() == AgentActionStatus.PENDING
                || latest.status() == AgentActionStatus.FAILED)
                && latest.version() != expectedVersion) {
            throw error(VERSION_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        throw error(VERSION_CONFLICT, VERSION_CONFLICT_MESSAGE);
    }

    private AgentPendingActionView toView(AgentPendingAction action) {
        Map<String, Object> target = AgentPendingActionView.sanitizeFinalResult(
                action.actionType(),
                codec.readMap(action.targetRefJson())
        );
        Map<String, Object> evidence = AgentPendingActionView.summarizeEvidence(
                codec.readMap(action.evidenceJson())
        );
        Map<String, Object> result = codec.readMap(action.resultJson());
        for (AgentActionViewEnricher enricher : enrichers) {
            try {
                Map<String, Object> enriched = enricher.enrich(
                        action,
                        new LinkedHashMap<>(result)
                );
                if (enriched == null) {
                    throw error(EXECUTION_FAILED, ENRICHMENT_FAILED_MESSAGE);
                }
                result = new LinkedHashMap<>(enriched);
            } catch (RuntimeException ex) {
                throw error(EXECUTION_FAILED, ENRICHMENT_FAILED_MESSAGE);
            }
        }
        result = AgentPendingActionView.sanitizeFinalResult(action.actionType(), result);

        Map<String, Object> failure = null;
        if (hasText(action.failureCode()) || hasText(action.failureMessage())) {
            Map<String, Object> sanitizedFailure = AgentPendingActionView.sanitizeFinalResult(
                    action.actionType(),
                    Map.of(
                            "code", safeFailureCode(action.failureCode()),
                            "message", limit(safeText(action.failureMessage()), MAX_FAILURE_MESSAGE_LENGTH)
                    )
            );
            failure = Map.of(
                    "code", sanitizedFailure.getOrDefault("code", EXECUTION_FAILED),
                    "message", sanitizedFailure.getOrDefault("message", EXECUTION_FAILED_MESSAGE)
            );
        }
        return new AgentPendingActionView(
                action.actionId(),
                action.agentType(),
                action.actionType().value(),
                action.status(),
                action.gid(),
                action.targetType(),
                target,
                action.title(),
                action.summary(),
                evidence,
                action.attemptCount(),
                action.version(),
                action.expireTime(),
                action.rejectionReason(),
                action.rejectionReviewAction(),
                result,
                failure
        );
    }

    private AgentPendingAction load(String actionId) {
        return repository.findByActionId(actionId)
                .orElseThrow(() -> error(NOT_FOUND, NOT_FOUND_MESSAGE));
    }

    private Duration executionLease() {
        AgentProperties.Action action = properties.getAction();
        if (action == null) {
            throw error(EXECUTION_FAILED, EXECUTION_FAILED_MESSAGE);
        }
        return Duration.ofSeconds(action.getExecutionLeaseSeconds());
    }

    private boolean isConfirmable(AgentPendingAction action, LocalDateTime now) {
        if (isExpired(action, now)) {
            return false;
        }
        return action.status() == AgentActionStatus.PENDING
                || action.status() == AgentActionStatus.FAILED;
    }

    private boolean isRejectable(AgentPendingAction action, LocalDateTime now) {
        return !isExpired(action, now)
                && (action.status() == AgentActionStatus.PENDING
                || action.status() == AgentActionStatus.FAILED);
    }

    private boolean isExpired(AgentPendingAction action, LocalDateTime now) {
        return action.expireTime() != null && !action.expireTime().isAfter(now);
    }

    private String normalizeReviewAction(String reviewAction) {
        if (reviewAction == null || reviewAction.isBlank()) {
            return null;
        }
        if ("IGNORE".equals(reviewAction) || "FALSE_POSITIVE".equals(reviewAction)) {
            return reviewAction;
        }
        throw error(REVIEW_ACTION_INVALID, REVIEW_ACTION_INVALID_MESSAGE);
    }

    private String stableFailureMessage(RuntimeException ex) {
        if (ex == null || !hasText(ex.getMessage())) {
            return EXECUTION_FAILED_MESSAGE;
        }
        return limit(safeText(ex.getMessage()), MAX_FAILURE_MESSAGE_LENGTH);
    }

    private String safeFailureCode(String code) {
        if (code != null && code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            return code;
        }
        return EXECUTION_FAILED;
    }

    private String safeText(String text) {
        if (!hasText(text)) {
            return EXECUTION_FAILED_MESSAGE;
        }
        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                null,
                Map.of("message", text)
        );
        Object message = sanitized.get("message");
        return message instanceof String value ? value : EXECUTION_FAILED_MESSAGE;
    }

    private String limit(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private AgentActionException error(String code, String message) {
        return new AgentActionException(code, message);
    }

    private static String newExecutionToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
