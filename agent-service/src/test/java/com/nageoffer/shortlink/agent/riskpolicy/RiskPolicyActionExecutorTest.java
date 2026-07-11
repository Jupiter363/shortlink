package com.nageoffer.shortlink.agent.riskpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionContext;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionExecutor;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPort;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionTypes;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPayloadV1;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyConfirmedActionCommand;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyConfirmedActionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskPolicyActionExecutorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 11, 10, 0);
    private static final AgentActionExecutionContext EXECUTION_CONTEXT = new AgentActionExecutionContext(
            new AgentActionActor("trusted-user", "user-id", "Trusted User", "g1"),
            "confirmed after review"
    );

    @ParameterizedTest
    @MethodSource("supportedActions")
    void executesAllSupportedTypesWithDeterministicCommandAndWhitelistedResult(
            AgentActionType actionType,
            RiskPolicyActionPayloadV1 payload
    ) throws Exception {
        RiskPolicyActionPort port = mock(RiskPolicyActionPort.class);
        when(port.execute(any())).thenReturn(new RiskPolicyConfirmedActionResult(
                "policy-action-fixed",
                "risk:policy:test:key",
                3L,
                "ACTIVE",
                "PENDING"
        ));
        RiskPolicyActionExecutor executor = new RiskPolicyActionExecutor(
                actionType,
                port,
                new ObjectMapper()
        );
        AgentPendingAction action = pendingAction("action-1", actionType, 1, payload);

        AgentActionExecutionResult executionResult = executor.execute(action, EXECUTION_CONTEXT);

        ArgumentCaptor<RiskPolicyConfirmedActionCommand> captor =
                ArgumentCaptor.forClass(RiskPolicyConfirmedActionCommand.class);
        verify(port).execute(captor.capture());
        RiskPolicyConfirmedActionCommand command = captor.getValue();
        assertThat(command.actionId()).isEqualTo("action-1");
        assertThat(command.policyId()).isEqualTo(
                "policy-action-" + UUID.nameUUIDFromBytes(
                        "action-1".getBytes(StandardCharsets.UTF_8)
                )
        );
        assertThat(command.idempotencyKey()).isEqualTo("manual:action-1");
        assertThat(command.payload()).isEqualTo(payload);
        assertThat(command.confirmedBy()).isEqualTo("trusted-user");
        assertThat(command.confirmationNote()).isEqualTo("confirmed after review");
        assertThat(executionResult.result()).containsOnly(
                Map.entry("policyId", "policy-action-fixed"),
                Map.entry("policyKey", "risk:policy:test:key"),
                Map.entry("policyVersion", 3L),
                Map.entry("policyStatus", "ACTIVE"),
                Map.entry("syncStatus", "PENDING")
        );
        assertThat(executor.actionType()).isEqualTo(actionType);
        assertThat(executor.replaySafe()).isTrue();
    }

    @Test
    void rejectsWrongPayloadVersionTypeMismatchMalformedJsonAndMissingTrustedActor() throws Exception {
        RiskPolicyActionPort port = mock(RiskPolicyActionPort.class);
        RiskPolicyActionExecutor executor = new RiskPolicyActionExecutor(
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                port,
                new ObjectMapper()
        );
        RiskPolicyActionPayloadV1 disable = disablePayload();

        assertInvalid(() -> executor.execute(
                pendingAction("action-version", RiskPolicyActionTypes.DISABLE_SHORT_LINK, 2, disable),
                EXECUTION_CONTEXT
        ));
        assertInvalid(() -> executor.execute(
                pendingAction("action-type", RiskPolicyActionTypes.BLOCK_IP, 1, blockPayload()),
                EXECUTION_CONTEXT
        ));
        AgentPendingAction malformed = withPayloadJson(
                pendingAction("action-malformed", RiskPolicyActionTypes.DISABLE_SHORT_LINK, 1, disable),
                "{\"action\":\"DISABLE_SHORT_LINK\",\"rawIp\":\"192.0.2.44\"}"
        );
        assertInvalid(() -> executor.execute(malformed, EXECUTION_CONTEXT));
        assertInvalid(() -> executor.execute(
                pendingAction("action-actor", RiskPolicyActionTypes.DISABLE_SHORT_LINK, 1, disable),
                new AgentActionExecutionContext(null, "note")
        ));
        assertInvalid(() -> executor.execute(
                withTargetKey(
                        pendingAction(
                                "action-target",
                                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                                1,
                                disable
                        ),
                        "nurl.ink/different"
                ),
                EXECUTION_CONTEXT
        ));

        verifyNoInteractions(port);
    }

    @Test
    void rejectsNullOrIncompletePortResultWithoutLeakingItsValues() throws Exception {
        RiskPolicyActionPort port = mock(RiskPolicyActionPort.class);
        RiskPolicyActionExecutor executor = new RiskPolicyActionExecutor(
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                port,
                new ObjectMapper()
        );
        AgentPendingAction action = pendingAction(
                "action-result",
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                1,
                disablePayload()
        );
        when(port.execute(any())).thenReturn(new RiskPolicyConfirmedActionResult(
                "policy-id",
                "",
                0L,
                "ACTIVE",
                "secret-owner-token"
        ));

        assertThatThrownBy(() -> executor.execute(action, EXECUTION_CONTEXT))
                .isInstanceOf(AgentActionException.class)
                .hasMessage("Risk policy action execution failed")
                .extracting(throwable -> ((AgentActionException) throwable).code())
                .isEqualTo("ACTION_EXECUTION_FAILED");
    }

    @Test
    void constructorRejectsUnsupportedOrMissingDependencies() {
        RiskPolicyActionPort port = mock(RiskPolicyActionPort.class);
        ObjectMapper objectMapper = new ObjectMapper();

        assertThatThrownBy(() -> new RiskPolicyActionExecutor(
                new AgentActionType("campaign.pause-placement"),
                port,
                objectMapper
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskPolicyActionExecutor(null, port, objectMapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskPolicyActionExecutor(
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                null,
                objectMapper
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RiskPolicyActionExecutor(
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                port,
                null
        )).isInstanceOf(NullPointerException.class);
    }

    private static Stream<Arguments> supportedActions() {
        return Stream.of(
                Arguments.of(RiskPolicyActionTypes.DISABLE_SHORT_LINK, disablePayload()),
                Arguments.of(RiskPolicyActionTypes.LIMIT_TIME_WINDOW, timeWindowPayload()),
                Arguments.of(RiskPolicyActionTypes.BLOCK_IP, blockPayload())
        );
    }

    private static RiskPolicyActionPayloadV1 disablePayload() {
        return payload(RiskPolicyAction.DISABLE_SHORT_LINK, null, null, List.of());
    }

    private static RiskPolicyActionPayloadV1 timeWindowPayload() {
        return payload(
                RiskPolicyAction.LIMIT_TIME_WINDOW,
                null,
                "Asia/Shanghai",
                List.of("08:00-12:00")
        );
    }

    private static RiskPolicyActionPayloadV1 blockPayload() {
        return payload(RiskPolicyAction.BLOCK_IP, "a".repeat(64), null, List.of());
    }

    private static RiskPolicyActionPayloadV1 payload(
            RiskPolicyAction action,
            String ipHash,
            String timezone,
            List<String> windows
    ) {
        return new RiskPolicyActionPayloadV1(
                action,
                "g1",
                "nurl.ink",
                "abc123",
                ipHash,
                timezone,
                windows,
                "Confirmed risk action",
                "event-1",
                "batch-1",
                null
        );
    }

    private AgentPendingAction pendingAction(
            String actionId,
            AgentActionType actionType,
            int payloadVersion,
            RiskPolicyActionPayloadV1 payload
    ) throws Exception {
        return new AgentPendingAction(
                1L,
                actionId,
                "security-risk",
                actionType,
                payloadVersion,
                AgentActionAuthorizationScope.GID,
                "",
                "g1",
                "SHORT_LINK",
                targetKey(payload),
                "{\"domain\":\"nurl.ink\",\"shortUri\":\"abc123\"}",
                "Review risk action",
                "Review persisted risk action",
                new ObjectMapper().writeValueAsString(payload.toSafeMap()),
                "payload-hash",
                "{\"riskScore\":95}",
                "proposal-idempotency",
                "active-slot",
                AgentActionStatus.EXECUTING,
                NOW.plusHours(1),
                2L,
                "execution-token",
                NOW.plusMinutes(5),
                1,
                "{}",
                "",
                "",
                "risk-analysis-worker",
                "trusted-user",
                NOW,
                "",
                null,
                "",
                null,
                "trace-1",
                "event-1",
                "batch-1",
                "session-1",
                NOW.minusMinutes(5),
                NOW
        );
    }

    private AgentPendingAction withPayloadJson(AgentPendingAction source, String payloadJson) {
        return new AgentPendingAction(
                source.id(), source.actionId(), source.agentType(), source.actionType(),
                source.payloadVersion(), source.authorizationScope(), source.ownerUsername(),
                source.gid(), source.targetType(), source.targetKey(), source.targetRefJson(),
                source.title(), source.summary(), payloadJson, source.payloadHash(),
                source.evidenceJson(), source.idempotencyKey(), source.activeSlotKey(),
                source.status(), source.expireTime(), source.version(), source.executionToken(),
                source.executionLeaseUntil(), source.attemptCount(), source.resultJson(),
                source.failureCode(), source.failureMessage(), source.proposedBy(),
                source.confirmedBy(), source.confirmedTime(), source.rejectedBy(),
                source.rejectedTime(), source.rejectionReason(), source.rejectionReviewAction(),
                source.traceId(), source.eventId(), source.batchId(), source.sessionId(),
                source.createTime(), source.updateTime()
        );
    }

    private AgentPendingAction withTargetKey(AgentPendingAction source, String targetKey) {
        return new AgentPendingAction(
                source.id(), source.actionId(), source.agentType(), source.actionType(),
                source.payloadVersion(), source.authorizationScope(), source.ownerUsername(),
                source.gid(), source.targetType(), targetKey, source.targetRefJson(),
                source.title(), source.summary(), source.payloadJson(), source.payloadHash(),
                source.evidenceJson(), source.idempotencyKey(), source.activeSlotKey(),
                source.status(), source.expireTime(), source.version(), source.executionToken(),
                source.executionLeaseUntil(), source.attemptCount(), source.resultJson(),
                source.failureCode(), source.failureMessage(), source.proposedBy(),
                source.confirmedBy(), source.confirmedTime(), source.rejectedBy(),
                source.rejectedTime(), source.rejectionReason(), source.rejectionReviewAction(),
                source.traceId(), source.eventId(), source.batchId(), source.sessionId(),
                source.createTime(), source.updateTime()
        );
    }

    private String targetKey(RiskPolicyActionPayloadV1 payload) {
        String target = payload.domain() + "/" + payload.shortUri();
        return payload.action() == RiskPolicyAction.BLOCK_IP
                ? target + "#" + payload.ipHash()
                : target;
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(AgentActionException.class)
                .hasMessage("Risk policy action payload is invalid")
                .extracting(throwable -> ((AgentActionException) throwable).code())
                .isEqualTo("ACTION_PAYLOAD_INVALID");
    }
}
