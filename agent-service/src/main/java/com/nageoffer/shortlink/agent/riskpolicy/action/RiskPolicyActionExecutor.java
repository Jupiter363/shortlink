package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutor;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionContext;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RiskPolicyActionExecutor implements AgentActionExecutor {

    private static final int SUPPORTED_PAYLOAD_VERSION = 1;
    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE = "Risk policy action payload is invalid";
    private static final String EXECUTION_FAILED_CODE = "ACTION_EXECUTION_FAILED";
    private static final String EXECUTION_FAILED_MESSAGE = "Risk policy action execution failed";
    private static final Set<AgentActionType> SUPPORTED_TYPES = Set.of(
            RiskPolicyActionTypes.DISABLE_SHORT_LINK,
            RiskPolicyActionTypes.LIMIT_TIME_WINDOW,
            RiskPolicyActionTypes.BLOCK_IP
    );
    private static final RiskPolicyActionPayloadValidator PAYLOAD_VALIDATOR =
            new RiskPolicyActionPayloadValidator();

    private final AgentActionType actionType;

    private final RiskPolicyActionPort actionPort;

    private final ObjectMapper objectMapper;

    public RiskPolicyActionExecutor(
            AgentActionType actionType,
            RiskPolicyActionPort actionPort,
            ObjectMapper objectMapper
    ) {
        if (actionType == null || !SUPPORTED_TYPES.contains(actionType)) {
            throw new IllegalArgumentException("Unsupported risk policy action type");
        }
        this.actionType = actionType;
        this.actionPort = Objects.requireNonNull(actionPort, "actionPort must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    }

    @Override
    public AgentActionType actionType() {
        return actionType;
    }

    @Override
    public boolean replaySafe() {
        return true;
    }

    @Override
    public AgentActionExecutionResult execute(
            AgentPendingAction action,
            AgentActionExecutionContext context
    ) {
        RiskPolicyActionPayloadV1 payload = validateAndReadPayload(action, context);
        String actionId = action.actionId();
        RiskPolicyConfirmedActionCommand command = new RiskPolicyConfirmedActionCommand(
                actionId,
                deterministicPolicyId(actionId),
                "manual:" + actionId,
                payload,
                context.actor().username(),
                context.note(),
                action.traceId(),
                action.sessionId()
        );
        RiskPolicyConfirmedActionResult result = actionPort.execute(command);
        if (!validResult(result)) {
            throw executionFailed();
        }
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("policyId", result.policyId());
        resultMap.put("policyKey", result.policyKey());
        resultMap.put("policyVersion", result.policyVersion());
        resultMap.put("policyStatus", result.policyStatus());
        resultMap.put("syncStatus", result.syncStatus());
        return new AgentActionExecutionResult(resultMap);
    }

    private RiskPolicyActionPayloadV1 validateAndReadPayload(
            AgentPendingAction action,
            AgentActionExecutionContext context
    ) {
        if (action == null
                || context == null
                || context.actor() == null
                || !hasText(context.actor().username())
                || !hasText(action.actionId())
                || !"security-risk".equals(action.agentType())
                || action.authorizationScope() != AgentActionAuthorizationScope.GID
                || !"SHORT_LINK".equals(action.targetType())
                || action.status() != AgentActionStatus.EXECUTING
                || !actionType.equals(action.actionType())
                || action.payloadVersion() != SUPPORTED_PAYLOAD_VERSION
                || !hasText(action.payloadJson())) {
            throw invalidPayload();
        }

        RiskPolicyActionPayloadV1 payload;
        try {
            payload = objectMapper.readValue(action.payloadJson(), RiskPolicyActionPayloadV1.class);
            PAYLOAD_VALIDATOR.validate(payload);
        } catch (JsonProcessingException | RuntimeException ignored) {
            throw invalidPayload();
        }
        if (payload.action() != expectedPolicyAction()
                || !payload.gid().equals(action.gid())
                || !payload.eventId().equals(action.eventId())
                || !valueOrEmpty(payload.batchId()).equals(valueOrEmpty(action.batchId()))
                || !expectedTargetKey(payload).equals(action.targetKey())) {
            throw invalidPayload();
        }
        return payload;
    }

    private String expectedTargetKey(RiskPolicyActionPayloadV1 payload) {
        String targetKey = payload.domain() + "/" + payload.shortUri();
        return payload.action() == RiskPolicyAction.BLOCK_IP
                ? targetKey + "#" + payload.ipHash()
                : targetKey;
    }

    private RiskPolicyAction expectedPolicyAction() {
        if (RiskPolicyActionTypes.DISABLE_SHORT_LINK.equals(actionType)) {
            return RiskPolicyAction.DISABLE_SHORT_LINK;
        }
        if (RiskPolicyActionTypes.LIMIT_TIME_WINDOW.equals(actionType)) {
            return RiskPolicyAction.LIMIT_TIME_WINDOW;
        }
        return RiskPolicyAction.BLOCK_IP;
    }

    private boolean validResult(RiskPolicyConfirmedActionResult result) {
        return result != null
                && hasText(result.policyId())
                && hasText(result.policyKey())
                && result.policyVersion() > 0
                && hasText(result.policyStatus())
                && hasText(result.syncStatus());
    }

    private String deterministicPolicyId(String actionId) {
        return "policy-action-" + UUID.nameUUIDFromBytes(
                actionId.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private AgentActionException invalidPayload() {
        return new AgentActionException(INVALID_CODE, INVALID_MESSAGE);
    }

    private AgentActionException executionFailed() {
        return new AgentActionException(EXECUTION_FAILED_CODE, EXECUTION_FAILED_MESSAGE);
    }
}
