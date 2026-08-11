package com.nageoffer.shortlink.admin.authority.capability.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupStatsQueryRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupStatsQueryResponse;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentGroupStatsCapabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void returnsAuthorizedVersionedSnapshotWithCanonicalHash() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.groupShortLinkStats("g1", "2026-07-10", "2026-07-16"))
                .thenReturn(Results.success(ShortLinkStatsRespDTO.builder()
                        .pv(100)
                        .uv(80)
                        .uip(60)
                        .build()));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        GroupStatsQueryResponse response = service(groupService, remoteService)
                .query(request(), "req-1");

        assertThat(response.schemaVersion()).isEqualTo("1.0");
        assertThat(response.requestId()).isEqualTo("req-1");
        assertThat(response.data()).isEqualTo(new GroupStatsQueryResponse.Data("g1", 100, 80, 60));
        assertThat(response.snapshot().snapshotId()).startsWith("snap-");
        assertThat(response.snapshot().source()).isEqualTo("admin/group-stats");
        assertThat(response.snapshot().observedAt()).isEqualTo(NOW);
        assertThat(response.snapshot().expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(response.snapshot().contentHash()).isEqualTo(
                "sha256:ecbef1fa10df7cd02d9ae2b5905a2a70a0adf4eea5524db58d74ddcd8d0a64fb"
        );
        assertThat(response.warnings()).isEmpty();
        verify(remoteService).groupShortLinkStats("g1", "2026-07-10", "2026-07-16");
    }

    @Test
    void rejectsGroupOutsideTrustedActorOwnershipBeforeProviderCall() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(0L);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService, remoteService).query(request(), "req-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CAPABILITY_FORBIDDEN");
                    assertThat(exception.status().value()).isEqualTo(403);
                });
        verify(remoteService, never()).groupShortLinkStats(any(), any(), any());
    }

    @Test
    void rejectsPartialDayRangeThatCannotBeRepresentedByDateProvider() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        GroupStatsQueryRequest invalidRequest = new GroupStatsQueryRequest(
                "g1",
                new GroupStatsQueryRequest.TimeRange(
                        OffsetDateTime.parse("2026-07-10T01:00:00+08:00"),
                        OffsetDateTime.parse("2026-07-17T00:00:00+08:00"),
                        "Asia/Shanghai"
                )
        );

        assertThatThrownBy(() -> service(groupService, remoteService).query(invalidRequest, "req-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception ->
                        assertThat(exception.code()).isEqualTo("VALIDATION_FAILED"));
        verify(remoteService, never()).groupShortLinkStats(any(), any(), any());
    }

    @Test
    void mapsMissingProviderDataToRetryableBadGateway() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.groupShortLinkStats("g1", "2026-07-10", "2026-07-16"))
                .thenReturn(Results.success(null));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService, remoteService).query(request(), "req-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CAPABILITY_PROVIDER_FAILED");
                    assertThat(exception.status().value()).isEqualTo(502);
                    assertThat(exception.retryable()).isTrue();
                });
    }

    private AgentGroupStatsCapabilityService service(
            GroupService groupService,
            ShortLinkActualRemoteService remoteService
    ) {
        return new AgentGroupStatsCapabilityService(
                groupService,
                remoteService,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private GroupStatsQueryRequest request() {
        return new GroupStatsQueryRequest(
                "g1",
                new GroupStatsQueryRequest.TimeRange(
                        OffsetDateTime.parse("2026-07-10T00:00:00+08:00"),
                        OffsetDateTime.parse("2026-07-17T00:00:00+08:00"),
                        "Asia/Shanghai"
                )
        );
    }
}
