package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jupiter.shortlink.admin.common.biz.user.*;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.remote.*;
import com.jupiter.shortlink.admin.remote.dto.req.*;
import com.jupiter.shortlink.admin.remote.dto.resp.*;
import com.jupiter.shortlink.admin.service.GroupService;
import com.jupiter.shortlink.admin.service.impl.RiskCenterFacadeServiceImpl;

import feign.FeignException;
import feign.Request;
import feign.Response;

import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

class RiskCenterControllerTest {
    private static final String TOKEN = "risk-center-component-internal-token-32";
    private final GroupService groups = mock(GroupService.class);
    private final AgentRiskRemoteService agent = mock(AgentRiskRemoteService.class);
    private final CommandRiskRemoteService command = mock(CommandRiskRemoteService.class);
    private RiskCenterController controller;

    @BeforeEach
    void setup() {
        var config = new AgentAdminConfiguration();
        config.setInternalToken(TOKEN);
        controller =
                new RiskCenterController(
                        new RiskCenterFacadeServiceImpl(agent, config, groups, command));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name", 1L));
        when(groups.count(any(Wrapper.class))).thenReturn(1L);
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    @Test
    void ownershipFailureStopsBeforeAgentOrCommand() {
        when(groups.count(any(Wrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> controller.groupShortLinks("other"))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(agent, command);
    }

    @Test
    void unavailableCommandPreservesLongCountsQualityAndUnknownState() {
        var card = card();
        when(agent.groupShortLinks(TOKEN, "trusted-user", "1001", 1L, "g1"))
                .thenReturn(Results.success(List.of(card)));
        when(command.current(any())).thenThrow(failure(503));
        var actual = controller.groupShortLinks("g1").getData().get(0);
        assertThat(actual.getPv2h()).isEqualTo(3_000_000_000L);
        assertThat(actual.getStatsMeta()).containsEntry("availability", "UNAVAILABLE");
        assertThat(actual.getCurrentPolicy()).containsEntry("state", "UNKNOWN");
        assertThat(actual.getLatestPolicyActions()).containsExactly("LIMIT_RATE");
    }

    @Test
    void currentFactOverridesHistoricalSuggestionWithoutClaimingPropagation() {
        var card = card();
        var detail = new RiskShortLinkDetailRespDTO();
        detail.setCard(card);
        detail.setLatestSnapshot(Map.of("policyStatus", "ACTIVE"));
        when(agent.shortLinkDetail(TOKEN, "trusted-user", "1001", 1L, "g1", "nurl.ink", "abc123"))
                .thenReturn(Results.success(detail));
        when(command.current(any()))
                .thenAnswer(
                        call -> {
                            long now = System.currentTimeMillis();
                            return List.of(
                                    new CommandRiskRemoteService.Snapshot(
                                            "1001:7",
                                            4,
                                            now,
                                            null,
                                            now + 1000,
                                            "KNOWN_ALLOWED",
                                            false,
                                            true,
                                            "UTC",
                                            List.of(),
                                            List.of(),
                                            null));
                        });
        var result = controller.shortLinkDetail("g1", "nurl.ink", "abc123").getData();
        assertThat(result.getCard().getCurrentPolicy())
                .containsEntry("state", "KNOWN_ALLOWED")
                .containsEntry("policyRevision", 4L)
                .containsEntry("propagationState", "NOT_OBSERVED");
        assertThat(result.getLatestSnapshot())
                .doesNotContainKey("policyStatus")
                .containsEntry("policyStatusSource", "HISTORICAL_ONLY");
    }

    @Test
    void stalePolicyLeaseIsUnknown() {
        var card = card();
        when(agent.groupShortLinks(any(), any(), any(), any(), any()))
                .thenReturn(Results.success(List.of(card)));
        when(command.current(any()))
                .thenReturn(
                        List.of(
                                new CommandRiskRemoteService.Snapshot(
                                        "1001:7",
                                        4,
                                        1,
                                        null,
                                        1001,
                                        "KNOWN_RESTRICTED",
                                        true,
                                        false,
                                        "UTC",
                                        List.of(),
                                        List.of(),
                                        null)));
        assertThat(controller.groupShortLinks("g1").getData().get(0).getCurrentPolicy())
                .containsEntry("state", "UNKNOWN");
    }

    @Test
    void returnedForeignIdentityNeverReachesPolicyLookup() {
        var card = card();
        card.setTenantId("2002");
        when(agent.groupShortLinks(any(), any(), any(), any(), any()))
                .thenReturn(Results.success(List.of(card)));
        assertThatThrownBy(() -> controller.groupShortLinks("g1"))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(command);
    }

    @Test
    void humanReviewIsLocalAndUsesTrustedReviewer() {
        var request = new RiskReviewReqDTO();
        request.setGid("g1");
        request.setReviewAction("FALSE_POSITIVE");
        request.setReviewer("spoof");
        when(agent.review(any(), any(), any(), any(), any()))
                .thenReturn(Results.success(new RiskReviewRespDTO()));
        controller.review(request);
        assertThat(request.getReviewer()).isEqualTo("trusted-user");
        verify(agent).review(TOKEN, "trusted-user", "1001", 1L, request);
        verifyNoInteractions(command);
    }

    @Test
    void explicitRevocationNeedsNoStatisticsAndReturnsCommitNotDisabled() {
        when(command.revoke(any()))
                .thenReturn(
                        new CommandRiskRemoteService.Receipt(
                                "manual-0001", "COMMITTED", "policy-1", 8, 123));
        var result = controller.disablePolicy("policy-1", revoke()).getData();
        assertThat(result)
                .containsEntry("commandState", "COMMITTED")
                .containsEntry("propagationState", "PENDING")
                .doesNotContainKey("disabled");
        verify(command).revoke(new CommandRiskRemoteService.Revoke("manual-0001", 7, "policy-1"));
        verifyNoInteractions(agent);
        verifyNoInteractions(
                groups); // Historical gid must not block revocation after a move/deleted group.
    }

    @Test
    void unknownCommitAcknowledgementPreservesCommandIdForReconciliation() {
        when(command.revoke(any())).thenThrow(failure(503));
        var result = controller.disablePolicy("policy-1", revoke()).getData();
        assertThat(result)
                .containsEntry("commandId", "manual-0001")
                .containsEntry("commandState", "PENDING_CONFIRMATION")
                .doesNotContainKey("disabled");
    }

    @Test
    void permissionFailureIsNotPresentedAsPendingCommit() {
        when(command.revoke(any())).thenThrow(failure(403));
        assertThatThrownBy(() -> controller.disablePolicy("policy-1", revoke()))
                .isInstanceOf(FeignException.Forbidden.class);
    }

    @Test
    void httpBoundaryRequiresTrustedIdentityAndIgnoresLegacySpoofing() throws Exception {
        UserContext.removeUser();
        var identity = mock(TrustedManagementIdentity.class);
        when(identity.verify("1001", "trusted-user", "1"))
                .thenReturn(new UserInfoDTO("1001", "trusted-user", "Trusted Name", 1L));
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .addFilters(new UserTransmitFilter(identity, TOKEN))
                        .build();
        when(command.revoke(any()))
                .thenReturn(
                        new CommandRiskRemoteService.Receipt(
                                "manual-0001", "COMMITTED", "policy-1", 8, 123));
        String path = "/api/short-link/admin/v1/risk/policies/policy-1/disable";
        String body =
                "{\"gid\":\"g1\",\"linkId\":7,\"commandId\":\"manual-0001\",\"reviewer\":\"spoof\"}";
        mvc.perform(
                        post(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("username", "spoof")
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Token", TOKEN)
                                .header("x-shortlink-tenant-id", "1001")
                                .header("x-shortlink-username", "trusted-user")
                                .header("x-shortlink-auth-version", "1")
                                .header("username", "spoof")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandState").value("COMMITTED"))
                .andExpect(jsonPath("$.data.disabled").doesNotExist());
        assertThat(UserContext.getUserId()).isNull();
    }

    private RiskPolicyDisableReqDTO revoke() {
        var r = new RiskPolicyDisableReqDTO();
        r.setGid("g1");
        r.setCommandId("manual-0001");
        r.setLinkId(7L);
        return r;
    }

    private RiskShortLinkCardRespDTO card() {
        var r = new RiskShortLinkCardRespDTO();
        r.setGid("g1");
        r.setTenantId("1001");
        r.setLinkId(7L);
        r.setDomain("nurl.ink");
        r.setShortUri("abc123");
        r.setPv2h(3_000_000_000L);
        r.setStatsMeta(Map.of("availability", "UNAVAILABLE", "provisional", true));
        r.setLatestPolicyActions(List.of("LIMIT_RATE"));
        return r;
    }

    private FeignException failure(int status) {
        var request =
                Request.create(
                        Request.HttpMethod.POST,
                        "http://command/internal",
                        Map.of(),
                        new byte[0],
                        java.nio.charset.StandardCharsets.UTF_8,
                        null);
        return FeignException.errorStatus(
                "command",
                Response.builder()
                        .status(status)
                        .reason("component failure")
                        .request(request)
                        .headers(Map.of())
                        .build());
    }
}
