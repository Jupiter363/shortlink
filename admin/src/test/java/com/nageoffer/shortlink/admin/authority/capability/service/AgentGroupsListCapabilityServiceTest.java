package com.nageoffer.shortlink.admin.authority.capability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupsListRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupsListResponse;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import com.nageoffer.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentGroupsListCapabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void returnsOwnedGroupsAsVersionedSnapshotWithCanonicalHash() {
        GroupService groupService = mock(GroupService.class);
        when(groupService.listGroup()).thenReturn(List.of(
                group("g1", "Marketing", 10, 3),
                group("g2", "Product", 5, 2)
        ));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        GroupsListResponse response = service(groupService).list(new GroupsListRequest(), "trace-1");

        assertThat(response.schemaVersion()).isEqualTo("1.0");
        assertThat(response.requestId()).isEqualTo("trace-1");
        assertThat(response.data()).containsExactly(
                new GroupsListResponse.Group("g1", "Marketing", 10, 3),
                new GroupsListResponse.Group("g2", "Product", 5, 2)
        );
        assertThat(response.snapshot().snapshotId()).startsWith("snap-");
        assertThat(response.snapshot().source()).isEqualTo("admin/groups");
        assertThat(response.snapshot().observedAt()).isEqualTo(NOW);
        assertThat(response.snapshot().expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(response.snapshot().contentHash()).isEqualTo(
                "sha256:eebc387e17eecf08b328f6213b57adcbb2a7b533aa811a0c4934334176fa6d33"
        );
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void rejectsMissingTrustedActorBeforeCallingBusinessProvider() {
        GroupService groupService = mock(GroupService.class);

        assertThatThrownBy(() -> service(groupService).list(new GroupsListRequest(), "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CAPABILITY_FORBIDDEN");
                    assertThat(exception.status().value()).isEqualTo(403);
                });
        verify(groupService, never()).listGroup();
    }

    @Test
    void rejectsInvalidProviderRowsInsteadOfPublishingUntrustedShape() {
        GroupService groupService = mock(GroupService.class);
        when(groupService.listGroup()).thenReturn(List.of(group("g1", "Marketing", 10, -1)));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService).list(new GroupsListRequest(), "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CAPABILITY_PROVIDER_FAILED");
                    assertThat(exception.status().value()).isEqualTo(502);
                    assertThat(exception.retryable()).isTrue();
                });
    }

    @Test
    void rejectsDuplicateProviderGidsInsteadOfPublishingAmbiguousOwnershipData() {
        GroupService groupService = mock(GroupService.class);
        when(groupService.listGroup()).thenReturn(List.of(
                group("g1", "Marketing", 10, 3),
                group("g1", "Duplicate", 5, 2)
        ));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService).list(new GroupsListRequest(), "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CAPABILITY_PROVIDER_FAILED"));
    }

    @Test
    void mapsBusinessProviderExceptionsToStableRetryableProblem() {
        GroupService groupService = mock(GroupService.class);
        when(groupService.listGroup()).thenThrow(new IllegalStateException("downstream detail"));
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        assertThatThrownBy(() -> service(groupService).list(new GroupsListRequest(), "trace-1"))
                .isInstanceOfSatisfying(AgentCapabilityException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CAPABILITY_PROVIDER_FAILED");
                    assertThat(exception.getMessage()).doesNotContain("downstream detail");
                });
    }

    private AgentGroupsListCapabilityService service(GroupService groupService) {
        return new AgentGroupsListCapabilityService(
                groupService,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ShortLinkGroupRespDTO group(
            String gid,
            String name,
            int sortOrder,
            int shortLinkCount
    ) {
        ShortLinkGroupRespDTO group = new ShortLinkGroupRespDTO();
        group.setGid(gid);
        group.setName(name);
        group.setSortOrder(sortOrder);
        group.setShortLinkCount(shortLinkCount);
        return group;
    }
}
