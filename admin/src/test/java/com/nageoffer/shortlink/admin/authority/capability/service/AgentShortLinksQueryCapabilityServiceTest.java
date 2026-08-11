package com.nageoffer.shortlink.admin.authority.capability.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;
import com.nageoffer.shortlink.admin.authority.capability.model.ShortLinksQueryRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.ShortLinksQueryResponse;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentShortLinksQueryCapabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void returnsAuthorizedMinimizedPageWithCanonicalHash() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.pageShortLink("g1", "totalPv", 2L, 2L))
                .thenReturn(Results.success(page(link("g1", 120))));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        ShortLinksQueryResponse response = service(groupService, remoteService).query(request(), "trace-1");

        assertThat(response.schemaVersion()).isEqualTo("1.0");
        assertThat(response.requestId()).isEqualTo("trace-1");
        assertThat(response.data().gid()).isEqualTo("g1");
        assertThat(response.data().current()).isEqualTo(2L);
        assertThat(response.data().size()).isEqualTo(2L);
        assertThat(response.data().total()).isEqualTo(3L);
        assertThat(response.data().pages()).isEqualTo(2L);
        assertThat(response.data().hasNext()).isFalse();
        assertThat(response.data().sort()).isEqualTo("TOTAL_PV_DESC");
        assertThat(response.data().records()).containsExactly(new ShortLinksQueryResponse.ShortLink(
                "nurl.ink/a",
                "Launch",
                "CUSTOM",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-07-10T01:00:00Z"),
                12,
                8,
                7,
                120,
                80,
                70
        ));
        assertThat(response.snapshot().source()).isEqualTo("admin/short-links");
        assertThat(response.snapshot().contentHash()).isEqualTo(
                "sha256:ea37044dd349379395be7b51a1e4b044f1bbc36f794ae4055666fa72995e878a"
        );
        assertThat(response.warnings()).isEmpty();
        verify(remoteService).pageShortLink("g1", "totalPv", 2L, 2L);
    }

    @Test
    void rejectsGroupOutsideTrustedActorOwnershipBeforeProviderCall() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(0L);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService, remoteService).query(request(), "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CAPABILITY_FORBIDDEN"));
        verify(remoteService, never()).pageShortLink(any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidPageBeforeOwnershipOrProviderCalls() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        ShortLinksQueryRequest invalid = new ShortLinksQueryRequest(
                "g1",
                1L,
                501L,
                ShortLinksQueryRequest.Sort.CREATED_AT_DESC
        );

        assertThatThrownBy(() -> service(groupService, remoteService).query(invalid, "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception ->
                        assertThat(exception.code()).isEqualTo("VALIDATION_FAILED"));
        verify(groupService, never()).count(any(Wrapper.class));
        verify(remoteService, never()).pageShortLink(any(), any(), any(), any());
    }

    @Test
    void rejectsProviderRowsFromAnotherGroupOrWithNegativeMetrics() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.pageShortLink("g1", "totalPv", 2L, 2L))
                .thenReturn(Results.success(page(link("other-group", -1))));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService, remoteService).query(request(), "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CAPABILITY_PROVIDER_FAILED");
                    assertThat(exception.status().value()).isEqualTo(502);
                });
    }

    @Test
    void rejectsCustomValidityThatDoesNotExtendPastCreation() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        ShortLinkPageRespDTO invalid = link("g1", 120);
        invalid.setValidDate(invalid.getCreateTime());
        when(remoteService.pageShortLink("g1", "totalPv", 2L, 2L))
                .thenReturn(Results.success(page(invalid)));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService, remoteService).query(request(), "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CAPABILITY_PROVIDER_FAILED");
                    assertThat(exception.status().value()).isEqualTo(502);
                });
    }

    @Test
    void mapsProviderExceptionToStableProblemWhileRetainingInternalCause() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        IllegalStateException cause = new IllegalStateException("downstream detail");
        when(remoteService.pageShortLink("g1", "totalPv", 2L, 2L)).thenThrow(cause);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService, remoteService).query(request(), "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CAPABILITY_PROVIDER_FAILED");
                    assertThat(exception.getMessage()).doesNotContain("downstream detail");
                    assertThat(exception.getCause()).isSameAs(cause);
                });
    }

    private AgentShortLinksQueryCapabilityService service(
            GroupService groupService,
            ShortLinkActualRemoteService remoteService
    ) {
        return new AgentShortLinksQueryCapabilityService(
                groupService,
                remoteService,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ShortLinksQueryRequest request() {
        return new ShortLinksQueryRequest(
                "g1",
                2L,
                2L,
                ShortLinksQueryRequest.Sort.TOTAL_PV_DESC
        );
    }

    private Page<ShortLinkPageRespDTO> page(ShortLinkPageRespDTO record) {
        Page<ShortLinkPageRespDTO> page = new Page<>(2, 2);
        page.setTotal(3);
        page.setRecords(List.of(record));
        return page;
    }

    private ShortLinkPageRespDTO link(String gid, int totalPv) {
        ShortLinkPageRespDTO link = new ShortLinkPageRespDTO();
        link.setId(99L);
        link.setGid(gid);
        link.setDomain("https://nurl.ink");
        link.setShortUri("a");
        link.setFullShortUrl("nurl.ink/a");
        link.setOriginUrl("https://origin.example/private?token=must-not-cross-boundary");
        link.setDescribe("Launch");
        link.setFavicon("https://origin.example/favicon.ico");
        link.setValidDateType(1);
        link.setValidDate(Date.from(Instant.parse("2026-08-01T00:00:00Z")));
        link.setCreateTime(Date.from(Instant.parse("2026-07-10T01:00:00Z")));
        link.setTodayPv(12);
        link.setTodayUv(8);
        link.setTodayUip(7);
        link.setTotalPv(totalPv);
        link.setTotalUv(80);
        link.setTotalUip(70);
        return link;
    }
}
