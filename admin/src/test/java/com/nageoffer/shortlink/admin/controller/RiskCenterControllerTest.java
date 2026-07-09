package com.nageoffer.shortlink.admin.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nageoffer.shortlink.admin.common.biz.user.UserTransmitFilter;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.remote.AgentRiskRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskPolicyDisableReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskReviewReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskGroupOverviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskReviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkCardRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkDetailRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import com.nageoffer.shortlink.admin.service.impl.RiskCenterFacadeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskCenterControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void groupOverviewChecksOwnedGidAndForwardsTrustedHeaders() {
        Fixture fixture = fixture();
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        when(fixture.groupService.count(any(Wrapper.class))).thenAnswer(invocation -> {
            assertThat(UserContext.getUsername()).isEqualTo("trusted-user");
            return 1L;
        });
        RiskGroupOverviewRespDTO overview = new RiskGroupOverviewRespDTO();
        overview.setGid("g1");
        Result<RiskGroupOverviewRespDTO> expected = Results.success(overview);
        when(fixture.remoteService.groupOverview("internal-token", "trusted-user", "1001", "Trusted Name", "g1"))
                .thenReturn(expected);

        Result<RiskGroupOverviewRespDTO> actual = fixture.controller.groupOverview("g1");

        assertThat(actual).isSameAs(expected);
        verify(fixture.remoteService).groupOverview("internal-token", "trusted-user", "1001", "Trusted Name", "g1");
    }

    @Test
    void groupShortLinksRejectsGidOutsideCurrentUserGroups() {
        Fixture fixture = fixture();
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        when(fixture.groupService.count(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> fixture.controller.groupShortLinks("other-gid"))
                .isInstanceOf(ClientException.class)
                .hasMessage("Risk center request gid is not owned by current user");
        verify(fixture.remoteService, never()).groupShortLinks(any(), any(), any(), any(), any());
    }

    @Test
    void shortLinkDetailRejectsGidOutsideCurrentUserBeforeRemoteLookup() {
        Fixture fixture = fixture();
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        when(fixture.groupService.count(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> fixture.controller.shortLinkDetail("g1", "nurl.ink", "abc123"))
                .isInstanceOf(ClientException.class)
                .hasMessage("Risk center request gid is not owned by current user");
        verify(fixture.remoteService, never()).shortLinkDetail(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shortLinkDetailRejectsSuccessfulResponseWithoutReturnedCard() {
        Fixture fixture = fixture();
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        RiskShortLinkDetailRespDTO detail = new RiskShortLinkDetailRespDTO();
        when(fixture.groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(fixture.remoteService.shortLinkDetail(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "g1",
                "nurl.ink",
                "abc123"
        )).thenReturn(Results.success(detail));

        assertThatThrownBy(() -> fixture.controller.shortLinkDetail("g1", "nurl.ink", "abc123"))
                .isInstanceOf(ClientException.class)
                .hasMessage("Risk center request gid is not owned by current user");
    }

    @Test
    void shortLinkDetailForwardsOwnedGidToAgentBeforeTargetLookup() {
        Fixture fixture = fixture();
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        when(fixture.groupService.count(any(Wrapper.class))).thenReturn(1L);
        RiskShortLinkCardRespDTO card = new RiskShortLinkCardRespDTO();
        card.setGid("g1");
        card.setDomain("nurl.ink");
        card.setShortUri("abc123");
        RiskShortLinkDetailRespDTO detail = new RiskShortLinkDetailRespDTO();
        detail.setCard(card);
        Result<RiskShortLinkDetailRespDTO> expected = Results.success(detail);
        when(fixture.remoteService.shortLinkDetail(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "g1",
                "nurl.ink",
                "abc123"
        )).thenReturn(expected);

        Result<RiskShortLinkDetailRespDTO> actual = fixture.controller.shortLinkDetail("g1", "nurl.ink", "abc123");

        assertThat(actual).isSameAs(expected);
        verify(fixture.remoteService).shortLinkDetail(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "g1",
                "nurl.ink",
                "abc123"
        );
    }

    @Test
    void eventsRequireOwnedGidAndForwardPagination() {
        Fixture fixture = fixture();
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        when(fixture.groupService.count(any(Wrapper.class))).thenReturn(1L);
        RiskPageRespDTO<?> page = new RiskPageRespDTO<>();
        page.setPageNo(2);
        page.setPageSize(20);
        page.setTotal(0L);
        Result<RiskPageRespDTO<?>> expected = Results.success(page);
        when(fixture.remoteService.events(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "g1",
                "SHORT_LINK",
                "nurl.ink",
                "abc123",
                2,
                20
        )).thenReturn(expected);

        Result<RiskPageRespDTO<?>> actual = fixture.controller.events(
                "g1",
                "SHORT_LINK",
                "nurl.ink",
                "abc123",
                2,
                20
        );

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void reviewUsesCurrentUserAsReviewerAndIgnoresSpoofedReviewer() {
        Fixture fixture = fixture();
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        when(fixture.groupService.count(any(Wrapper.class))).thenReturn(1L);
        RiskReviewReqDTO request = new RiskReviewReqDTO();
        request.setEventId("event-1");
        request.setTargetType("SHORT_LINK");
        request.setGid("g1");
        request.setDomain("nurl.ink");
        request.setShortUri("abc123");
        request.setFullShortUrl("nurl.ink/abc123");
        request.setReviewAction("WATCH");
        request.setReviewer("spoofed-user");
        request.setReviewNote("watch this link");
        RiskReviewRespDTO response = new RiskReviewRespDTO();
        response.setReviewId("review-1");
        when(fixture.remoteService.review(any(), any(), any(), any(), any()))
                .thenReturn(Results.success(response));

        Result<RiskReviewRespDTO> actual = fixture.controller.review(request);

        assertThat(actual.isSuccess()).isTrue();
        ArgumentCaptor<RiskReviewReqDTO> requestCaptor = ArgumentCaptor.forClass(RiskReviewReqDTO.class);
        verify(fixture.remoteService).review(
                eq("internal-token"),
                eq("trusted-user"),
                eq("1001"),
                eq("Trusted Name"),
                requestCaptor.capture()
        );
        assertThat(requestCaptor.getValue().getReviewer()).isEqualTo("trusted-user");
        assertThat(requestCaptor.getValue().getReviewNote()).isEqualTo("watch this link");
    }

    @Test
    void disablePolicyRequiresOwnedGidAndUsesCurrentUserAsReviewer() {
        Fixture fixture = fixture();
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        when(fixture.groupService.count(any(Wrapper.class))).thenReturn(1L);
        RiskPolicyDisableReqDTO request = new RiskPolicyDisableReqDTO();
        request.setGid("g1");
        request.setReviewer("spoofed-user");
        request.setReason("false positive");
        request.setTraceId("trace-1");
        when(fixture.remoteService.disablePolicy(any(), any(), any(), any(), any(), any()))
                .thenReturn(Results.success(Map.of("disabled", true)));

        Result<Map<String, Object>> actual = fixture.controller.disablePolicy("policy-1", request);

        assertThat(actual.isSuccess()).isTrue();
        ArgumentCaptor<RiskPolicyDisableReqDTO> requestCaptor = ArgumentCaptor.forClass(RiskPolicyDisableReqDTO.class);
        verify(fixture.remoteService).disablePolicy(
                eq("internal-token"),
                eq("trusted-user"),
                eq("1001"),
                eq("Trusted Name"),
                eq("policy-1"),
                requestCaptor.capture()
        );
        assertThat(requestCaptor.getValue().getReviewer()).isEqualTo("trusted-user");
        assertThat(requestCaptor.getValue().getReason()).isEqualTo("false positive");
        assertThat(requestCaptor.getValue().getTraceId()).isEqualTo("trace-1");
    }

    @Test
    void disablePolicyBindsGidFromJsonAndUsesCurrentUserAsReviewer() throws Exception {
        Fixture fixture = fixture();
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(fixture.controller)
                .addFilters(new UserTransmitFilter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(fixture.groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(fixture.remoteService.disablePolicy(any(), any(), any(), any(), any(), any()))
                .thenReturn(Results.success(Map.of("disabled", true)));

        mockMvc.perform(post("/api/short-link/admin/v1/risk/policies/policy-1/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("username", "trusted-user")
                        .header("userId", "1001")
                        .header("realName", "Trusted Name")
                        .content("""
                                {
                                  "gid": "g1",
                                  "reviewer": "spoofed-user",
                                  "reason": "false positive",
                                  "traceId": "trace-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        ArgumentCaptor<RiskPolicyDisableReqDTO> requestCaptor = ArgumentCaptor.forClass(RiskPolicyDisableReqDTO.class);
        verify(fixture.remoteService).disablePolicy(
                eq("internal-token"),
                eq("trusted-user"),
                eq("1001"),
                eq("Trusted Name"),
                eq("policy-1"),
                requestCaptor.capture()
        );
        assertThat(requestCaptor.getValue().getGid()).isEqualTo("g1");
        assertThat(requestCaptor.getValue().getReviewer()).isEqualTo("trusted-user");
    }

    @Test
    void riskCenterRequiresGatewayInjectedUserContext() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.controller.groupOverview("g1"))
                .isInstanceOf(ClientException.class)
                .hasMessage("Risk center request requires authenticated user context");
    }

    private Fixture fixture() {
        GroupService groupService = mock(GroupService.class);
        AgentRiskRemoteService remoteService = mock(AgentRiskRemoteService.class);
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken("internal-token");
        RiskCenterFacadeServiceImpl facadeService = new RiskCenterFacadeServiceImpl(
                remoteService,
                configuration,
                groupService
        );
        return new Fixture(groupService, remoteService, new RiskCenterController(facadeService));
    }

    private record Fixture(
            GroupService groupService,
            AgentRiskRemoteService remoteService,
            RiskCenterController controller
    ) {
    }
}
