package com.nageoffer.shortlink.admin.authority.capability.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.capability.service.AgentGroupStatsCapabilityService;
import com.nageoffer.shortlink.admin.authority.capability.service.AgentGroupsListCapabilityService;
import com.nageoffer.shortlink.admin.authority.capability.service.AgentShortLinksQueryCapabilityService;
import com.nageoffer.shortlink.admin.common.biz.agent.AgentInternalToolApiFilter;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentCapabilityInternalControllerMvcTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void groupStatsProviderContractUsesTrustedActorAndReturnsDirectSnapshotPayload() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenAnswer(invocation -> {
            assertThat(UserContext.getUsername()).isEqualTo("trusted-user");
            return 1L;
        });
        when(remoteService.groupShortLinkStats("g1", "2026-07-10", "2026-07-16"))
                .thenReturn(Results.success(ShortLinkStatsRespDTO.builder()
                        .pv(100)
                        .uv(80)
                        .uip(60)
                        .build()));
        MockMvc mockMvc = mockMvc(groupService, remoteService, "internal-token", false);

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Agent-UserId", "1001")
                        .header("X-Agent-Trace-ID", "trace-1")
                        .header("X-Request-ID", "req-1")
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.snapshot.source").value("admin/group-stats"))
                .andExpect(jsonPath("$.snapshot.contentHash").value(
                        "sha256:ecbef1fa10df7cd02d9ae2b5905a2a70a0adf4eea5524db58d74ddcd8d0a64fb"
                ))
                .andExpect(jsonPath("$.data.gid").value("g1"))
                .andExpect(jsonPath("$.data.pv").value(100))
                .andExpect(jsonPath("$.data.uv").value(80))
                .andExpect(jsonPath("$.data.uip").value(60))
                .andExpect(jsonPath("$.code").doesNotExist());

        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void groupsListProviderUsesTrustedActorAndReturnsDirectSnapshotPayload() throws Exception {
        GroupService groupService = mock(GroupService.class);
        when(groupService.listGroup()).thenAnswer(invocation -> {
            assertThat(UserContext.getUsername()).isEqualTo("trusted-user");
            return List.of(group("g1", "Marketing", 10, 3));
        });
        MockMvc mockMvc = mockMvc(
                groupService,
                mock(ShortLinkActualRemoteService.class),
                "internal-token",
                false
        );

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-capabilities/v1/groups/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Agent-UserId", "1001")
                        .header("X-Agent-Trace-ID", "trace-1")
                        .header("X-Request-ID", "trace-1")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.requestId").value("trace-1"))
                .andExpect(jsonPath("$.snapshot.source").value("admin/groups"))
                .andExpect(jsonPath("$.data[0].gid").value("g1"))
                .andExpect(jsonPath("$.data[0].name").value("Marketing"))
                .andExpect(jsonPath("$.data[0].sortOrder").value(10))
                .andExpect(jsonPath("$.data[0].shortLinkCount").value(3))
                .andExpect(jsonPath("$.code").doesNotExist());

        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void groupsListRejectsIdentityOrUnknownFieldsInRequestBody() throws Exception {
        GroupService groupService = mock(GroupService.class);
        MockMvc mockMvc = mockMvc(
                groupService,
                mock(ShortLinkActualRemoteService.class),
                "internal-token",
                false
        );

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-capabilities/v1/groups/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Request-ID", "trace-1")
                        .content("{\"username\":\"spoofed-user\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("trace-1"));

        verify(groupService, never()).listGroup();
    }

    @Test
    void shortLinksProviderMinimizesPageAndUsesTrustedActor() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenAnswer(invocation -> {
            assertThat(UserContext.getUsername()).isEqualTo("trusted-user");
            return 1L;
        });
        when(remoteService.pageShortLink("g1", "totalPv", 2L, 2L))
                .thenReturn(Results.success(shortLinksPage()));
        MockMvc mockMvc = mockMvc(groupService, remoteService, "internal-token", false);

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Request-ID", "trace-1")
                        .content(shortLinksRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.source").value("admin/short-links"))
                .andExpect(jsonPath("$.snapshot.contentHash").value(
                        "sha256:ea37044dd349379395be7b51a1e4b044f1bbc36f794ae4055666fa72995e878a"
                ))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records[0].fullShortUrl").value("nurl.ink/a"))
                .andExpect(jsonPath("$.data.records[0].describe").value("Launch"))
                .andExpect(jsonPath("$.data.records[0].originUrl").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].favicon").doesNotExist());
    }

    @Test
    void shortLinksProviderRejectsIdentityInjectionInBody() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        MockMvc mockMvc = mockMvc(groupService, remoteService, "internal-token", false);

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Request-ID", "trace-1")
                        .content("""
                                {
                                  "gid": "g1",
                                  "current": 2,
                                  "size": 2,
                                  "sort": "TOTAL_PV_DESC",
                                  "owner": "spoofed-user"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(groupService, never()).count(any(Wrapper.class));
        verify(remoteService, never()).pageShortLink(any(), any(), any(), any());
    }

    @Test
    void groupStatsCapabilityRejectsMissingInternalTokenBeforeController() throws Exception {
        MockMvc mockMvc = mockMvc(mock(GroupService.class), mock(ShortLinkActualRemoteService.class),
                "internal-token", false);

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Username", "trusted-user")
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
                .andExpect(jsonPath("$.detail").value("Invalid internal token"));
    }

    @Test
    void groupStatsCapabilityReturnsProblemDetailsForInvalidRange() throws Exception {
        MockMvc mockMvc = mockMvc(mock(GroupService.class), mock(ShortLinkActualRemoteService.class),
                "internal-token", false);

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "trusted-user")
                        .header("X-Agent-Trace-ID", "trace-1")
                        .header("X-Request-ID", "req-invalid")
                        .content("""
                                {
                                  "gid": "g1",
                                  "timeRange": {
                                    "start": "2026-07-10T01:00:00+08:00",
                                    "end": "2026-07-17T00:00:00+08:00",
                                    "timezone": "Asia/Shanghai"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.requestId").value("req-invalid"))
                .andExpect(jsonPath("$.retryable").value(false));

        assertThat(UserContext.getUsername()).isNull();
    }

    private MockMvc mockMvc(
            GroupService groupService,
            ShortLinkActualRemoteService remoteService,
            String internalToken,
            boolean devMode
    ) {
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken(internalToken);
        configuration.setInternalTokenDevMode(devMode);
        AgentGroupStatsCapabilityService service = new AgentGroupStatsCapabilityService(
                groupService,
                remoteService,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-17T02:00:00Z"), ZoneOffset.UTC)
        );
        AgentGroupsListCapabilityService groupsListService = new AgentGroupsListCapabilityService(
                groupService,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-17T02:00:00Z"), ZoneOffset.UTC)
        );
        AgentShortLinksQueryCapabilityService shortLinksQueryService =
                new AgentShortLinksQueryCapabilityService(
                        groupService,
                        remoteService,
                        new ObjectMapper(),
                        Clock.fixed(Instant.parse("2026-07-17T02:00:00Z"), ZoneOffset.UTC)
                );
        return MockMvcBuilders
                .standaloneSetup(new AgentCapabilityInternalController(
                        service,
                        groupsListService,
                        shortLinksQueryService
                ))
                .addFilters(new AgentInternalToolApiFilter(configuration))
                .setControllerAdvice(new AgentCapabilityExceptionHandler())
                .build();
    }

    private String shortLinksRequest() throws Exception {
        return Files.readString(repositoryRoot().resolve(
                "schemas/agent-capabilities/v1/examples/short-links-query-request.json"
        ));
    }

    private Page<ShortLinkPageRespDTO> shortLinksPage() {
        ShortLinkPageRespDTO link = new ShortLinkPageRespDTO();
        link.setId(99L);
        link.setGid("g1");
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
        link.setTotalPv(120);
        link.setTotalUv(80);
        link.setTotalUip(70);
        Page<ShortLinkPageRespDTO> page = new Page<>(2, 2);
        page.setTotal(3);
        page.setRecords(List.of(link));
        return page;
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

    private String validRequest() throws Exception {
        Path repositoryRoot = repositoryRoot();
        return Files.readString(repositoryRoot.resolve(
                "schemas/agent-capabilities/v1/examples/group-stats-query-request.json"
        ));
    }

    private Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("schemas"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("Repository schemas directory was not found");
        }
        return candidate;
    }
}
