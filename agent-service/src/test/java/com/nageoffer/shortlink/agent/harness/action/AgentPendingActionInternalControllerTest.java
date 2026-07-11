package com.nageoffer.shortlink.agent.harness.action;

import com.nageoffer.shortlink.agent.harness.action.api.AgentActionExceptionHandler;
import com.nageoffer.shortlink.agent.harness.action.api.AgentPendingActionInternalController;
import com.nageoffer.shortlink.agent.harness.action.api.dto.AgentActionPageRespDTO;
import com.nageoffer.shortlink.agent.harness.action.api.dto.AgentPendingActionRespDTO;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionPage;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentPendingActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentPendingActionInternalControllerTest {

    private AgentPendingActionService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AgentPendingActionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentPendingActionInternalController(service))
                .setControllerAdvice(new AgentActionExceptionHandler())
                .build();
    }

    @Test
    void confirmUsesTrustedHeadersAndIgnoresBodyIdentity() throws Exception {
        AgentPendingActionView executed = actionView(AgentActionStatus.EXECUTED);
        when(service.confirm(anyString(), anyLong(), any(AgentActionActor.class), anyString()))
                .thenReturn(executed);

        mockMvc.perform(post("/internal/short-link-agent/v1/actions/action-1/confirm")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Agent-UserId", "1001")
                        .header("X-Agent-RealName", "Trusted User")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedGid": "g1",
                                  "expectedVersion": 1,
                                  "note": "confirm",
                                  "username": "spoofed-user",
                                  "userId": "spoofed-id",
                                  "realName": "Spoofed User",
                                  "reviewer": "spoofed-reviewer",
                                  "confirmedBy": "spoofed-confirmer",
                                  "rejectedBy": "spoofed-rejecter"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("EXECUTED"));

        verify(service).confirm(
                "action-1",
                1L,
                new AgentActionActor("trusted-user", "1001", "Trusted User", "g1"),
                "confirm"
        );
    }

    @Test
    void confirmDoesNotFallbackOptionalIdentityHeadersFromBody() throws Exception {
        when(service.confirm(anyString(), anyLong(), any(AgentActionActor.class), anyString()))
                .thenReturn(actionView(AgentActionStatus.EXECUTED));

        mockMvc.perform(post("/internal/short-link-agent/v1/actions/action-1/confirm")
                        .header("X-Agent-Username", "trusted-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedGid": "g1",
                                  "expectedVersion": 2,
                                  "note": "confirm",
                                  "userId": "spoofed-id",
                                  "realName": "Spoofed User"
                                }
                                """))
                .andExpect(status().isOk());

        verify(service).confirm(
                "action-1",
                2L,
                new AgentActionActor("trusted-user", null, null, "g1"),
                "confirm"
        );
    }

    @Test
    void rejectUsesTrustedHeadersAndPassesReviewActionAsString() throws Exception {
        when(service.reject(
                anyString(),
                anyLong(),
                any(AgentActionActor.class),
                anyString(),
                anyString()
        )).thenReturn(actionView(AgentActionStatus.REJECTED));

        mockMvc.perform(post("/internal/short-link-agent/v1/actions/action-1/reject")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Agent-UserId", "1001")
                        .header("X-Agent-RealName", "Trusted User")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedGid": "g1",
                                  "expectedVersion": 1,
                                  "reason": "known campaign",
                                  "reviewAction": "FALSE_POSITIVE",
                                  "username": "spoofed-user",
                                  "userId": "spoofed-id",
                                  "realName": "Spoofed User",
                                  "reviewer": "spoofed-reviewer",
                                  "confirmedBy": "spoofed-confirmer",
                                  "rejectedBy": "spoofed-rejecter"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("0"));

        verify(service).reject(
                "action-1",
                1L,
                new AgentActionActor("trusted-user", "1001", "Trusted User", "g1"),
                "known campaign",
                "FALSE_POSITIVE"
        );
    }

    @ParameterizedTest(name = "{0} requires X-Agent-Username")
    @MethodSource("requestsWithoutUsername")
    void endpointsRejectMissingTrustedUsername(
            String endpoint,
            MockHttpServletRequestBuilder request
    ) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACTION_SCOPE_FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Agent action access is forbidden"));

        verifyNoInteractions(service);
    }

    @Test
    void blankTrustedUsernameIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(post("/internal/short-link-agent/v1/actions/action-1/confirm")
                        .header("X-Agent-Username", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedGid": "g1",
                                  "expectedVersion": 1,
                                  "note": "confirm"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_SCOPE_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void listBoundsPageSizeAndReturnsOnlyWhitelistedFields() throws Exception {
        AgentActionActor actor = new AgentActionActor(
                "trusted-user",
                "1001",
                "Trusted User",
                "g1"
        );
        AgentActionPage<AgentPendingActionView> page = new AgentActionPage<>(
                List.of(actionView(AgentActionStatus.PENDING)),
                1L,
                2,
                100
        );
        when(service.page(
                eq("g1"),
                eq("risk-agent"),
                eq("risk.block-ip"),
                eq(AgentActionStatus.PENDING),
                eq(2),
                eq(100),
                eq(actor)
        )).thenReturn(page);

        MvcResult result = mockMvc.perform(get("/internal/short-link-agent/v1/actions")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Agent-UserId", "1001")
                        .header("X-Agent-RealName", "Trusted User")
                        .param("gid", "g1")
                        .param("agentType", "risk-agent")
                        .param("actionType", "risk.block-ip")
                        .param("status", "PENDING")
                        .param("pageNo", "2")
                        .param("pageSize", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNo").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.records[0].actionId").value("action-1"))
                .andExpect(jsonPath("$.data.records[0].evidenceSummary.reasonCodes[0]")
                        .value("TRAFFIC_SPIKE"))
                .andReturn();

        verify(service).page(
                "g1",
                "risk-agent",
                "risk.block-ip",
                AgentActionStatus.PENDING,
                2,
                100,
                actor
        );
        assertNoInternalFields(result.getResponse().getContentAsString());
    }

    @Test
    void listUsesDefaultPaginationAndOptionalFilters() throws Exception {
        AgentActionActor actor = new AgentActionActor("trusted-user", null, null, "g1");
        when(service.page(
                eq("g1"),
                isNull(),
                isNull(),
                isNull(),
                eq(1),
                eq(10),
                eq(actor)
        )).thenReturn(new AgentActionPage<>(List.of(), 0L, 1, 10));

        mockMvc.perform(get("/internal/short-link-agent/v1/actions")
                        .header("X-Agent-Username", "trusted-user")
                        .param("gid", "g1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.pageNo").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));

        verify(service).page("g1", null, null, null, 1, 10, actor);
    }

    @Test
    void detailPassesExpectedGidDirectlyToServiceAndWhitelistsResponse() throws Exception {
        AgentActionActor actor = new AgentActionActor(
                "trusted-user",
                "1001",
                "Trusted User",
                "g1"
        );
        when(service.detail("action-1", actor))
                .thenReturn(actionView(AgentActionStatus.PENDING));

        MvcResult result = mockMvc.perform(get("/internal/short-link-agent/v1/actions/action-1")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Agent-UserId", "1001")
                        .header("X-Agent-RealName", "Trusted User")
                        .param("expectedGid", "g1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionId").value("action-1"))
                .andExpect(jsonPath("$.data.gid").value("g1"))
                .andReturn();

        verify(service).detail("action-1", actor);
        verifyNoMoreInteractions(service);
        assertNoInternalFields(result.getResponse().getContentAsString());
    }

    @Test
    void listRequiresGid() throws Exception {
        mockMvc.perform(get("/internal/short-link-agent/v1/actions")
                        .header("X-Agent-Username", "trusted-user"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void detailRequiresExpectedGid() throws Exception {
        mockMvc.perform(get("/internal/short-link-agent/v1/actions/action-1")
                        .header("X-Agent-Username", "trusted-user"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void confirmReturnsAcceptedWithCurrentActionWhileExecuting() throws Exception {
        AgentActionActor actor = new AgentActionActor("trusted-user", null, null, "g1");
        when(service.confirm("action-1", 3L, actor, "confirm"))
                .thenReturn(actionView(AgentActionStatus.EXECUTING));

        mockMvc.perform(post("/internal/short-link-agent/v1/actions/action-1/confirm")
                        .header("X-Agent-Username", "trusted-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedGid": "g1",
                                  "expectedVersion": 3,
                                  "note": "confirm"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACTION_EXECUTING"))
                .andExpect(jsonPath("$.data.actionId").value("action-1"))
                .andExpect(jsonPath("$.data.status").value("EXECUTING"));
    }

    @Test
    void invalidReviewActionIsPassedToServiceAndReturnsStableBadRequest() throws Exception {
        AgentActionActor actor = new AgentActionActor("trusted-user", null, null, "g1");
        when(service.reject("action-1", 1L, actor, "reason", "INVALID"))
                .thenThrow(new AgentActionException(
                        "ACTION_REVIEW_ACTION_INVALID",
                        "Agent action review action is invalid"
                ));

        mockMvc.perform(post("/internal/short-link-agent/v1/actions/action-1/reject")
                        .header("X-Agent-Username", "trusted-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedGid": "g1",
                                  "expectedVersion": 1,
                                  "reason": "reason",
                                  "reviewAction": "INVALID"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACTION_REVIEW_ACTION_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("Agent action review action is invalid"));

        verify(service).reject("action-1", 1L, actor, "reason", "INVALID");
    }

    @ParameterizedTest(name = "{0} maps to HTTP {1}")
    @MethodSource("agentActionErrorMappings")
    void mapsAgentActionExceptionsToStableHttpResponses(
            String code,
            int expectedStatus
    ) throws Exception {
        when(service.detail(eq("action-1"), any(AgentActionActor.class)))
                .thenThrow(new AgentActionException(
                        code,
                        "stable message",
                        new IllegalStateException("secret cause")
                ));

        MvcResult result = mockMvc.perform(get("/internal/short-link-agent/v1/actions/action-1")
                        .header("X-Agent-Username", "trusted-user")
                        .param("expectedGid", "g1"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value("stable message"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret cause")
                .doesNotContain("IllegalStateException")
                .doesNotContain("stackTrace");
    }

    @Test
    void pageResponseDefensivelyCopiesRecords() {
        List<AgentPendingActionRespDTO> records = new ArrayList<>();
        records.add(AgentPendingActionRespDTO.from(actionView(AgentActionStatus.PENDING)));

        AgentActionPageRespDTO response = new AgentActionPageRespDTO(records, 1L, 1, 10);
        records.clear();

        assertThat(response.records()).hasSize(1);
        assertThatThrownBy(() -> response.records().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Stream<Arguments> requestsWithoutUsername() {
        return Stream.of(
                Arguments.of(
                        "list",
                        get("/internal/short-link-agent/v1/actions").param("gid", "g1")
                ),
                Arguments.of(
                        "detail",
                        get("/internal/short-link-agent/v1/actions/action-1")
                                .param("expectedGid", "g1")
                ),
                Arguments.of(
                        "confirm",
                        post("/internal/short-link-agent/v1/actions/action-1/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "expectedGid": "g1",
                                          "expectedVersion": 1,
                                          "note": "confirm"
                                        }
                                        """)
                ),
                Arguments.of(
                        "reject",
                        post("/internal/short-link-agent/v1/actions/action-1/reject")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "expectedGid": "g1",
                                          "expectedVersion": 1,
                                          "reason": "reason",
                                          "reviewAction": "FALSE_POSITIVE"
                                        }
                                        """)
                )
        );
    }

    private static Stream<Arguments> agentActionErrorMappings() {
        return Stream.of(
                Arguments.of("ACTION_PAYLOAD_INVALID", 400),
                Arguments.of("ACTION_REVIEW_ACTION_INVALID", 400),
                Arguments.of("ACTION_SCOPE_FORBIDDEN", 403),
                Arguments.of("ACTION_NOT_FOUND", 404),
                Arguments.of("ACTION_PAYLOAD_CONFLICT", 409),
                Arguments.of("ACTION_NOT_CONFIRMABLE", 409),
                Arguments.of("ACTION_VERSION_CONFLICT", 409),
                Arguments.of("ACTION_EXECUTING", 202),
                Arguments.of("ACTION_EXECUTION_FAILED", 500),
                Arguments.of("ACTION_EXECUTOR_UNAVAILABLE", 500),
                Arguments.of("ACTION_UNKNOWN", 500)
        );
    }

    private AgentPendingActionView actionView(AgentActionStatus status) {
        return new AgentPendingActionView(
                "action-1",
                "risk-agent",
                "risk.block-ip",
                status,
                "g1",
                "SHORT_LINK",
                Map.of("domain", "nurl.ink", "shortUri", "abc123"),
                "Block risky short link",
                "Traffic is concentrated",
                Map.of("reasonCodes", List.of("TRAFFIC_SPIKE"), "maskedIp", "203.0.*.*"),
                1,
                3L,
                LocalDateTime.of(2026, 7, 11, 20, 0),
                status == AgentActionStatus.REJECTED ? "known campaign" : null,
                status == AgentActionStatus.REJECTED ? "FALSE_POSITIVE" : null,
                status == AgentActionStatus.EXECUTED
                        ? Map.of("policyId", "policy-1", "effective", true)
                        : Map.of(),
                null
        );
    }

    private void assertNoInternalFields(String responseBody) {
        assertThat(responseBody)
                .doesNotContain("payloadJson")
                .doesNotContain("executionToken")
                .doesNotContain("evidenceJson")
                .doesNotContain("resultJson")
                .doesNotContain("targetRefJson")
                .doesNotContain("failureMessage")
                .doesNotContain("ownerUsername");
    }
}
