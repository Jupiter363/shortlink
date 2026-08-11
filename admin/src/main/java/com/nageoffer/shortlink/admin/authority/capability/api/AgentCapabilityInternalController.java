package com.nageoffer.shortlink.admin.authority.capability.api;

import com.nageoffer.shortlink.admin.authority.capability.model.GroupStatsQueryRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupStatsQueryResponse;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupsListRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupsListResponse;
import com.nageoffer.shortlink.admin.authority.capability.model.ShortLinksQueryRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.ShortLinksQueryResponse;
import com.nageoffer.shortlink.admin.authority.capability.service.AgentGroupStatsCapabilityService;
import com.nageoffer.shortlink.admin.authority.capability.service.AgentGroupsListCapabilityService;
import com.nageoffer.shortlink.admin.authority.capability.service.AgentShortLinksQueryCapabilityService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentCapabilityInternalController {

    private final AgentGroupStatsCapabilityService groupStatsCapabilityService;

    private final AgentGroupsListCapabilityService groupsListCapabilityService;

    private final AgentShortLinksQueryCapabilityService shortLinksQueryCapabilityService;

    public AgentCapabilityInternalController(
            AgentGroupStatsCapabilityService groupStatsCapabilityService,
            AgentGroupsListCapabilityService groupsListCapabilityService,
            AgentShortLinksQueryCapabilityService shortLinksQueryCapabilityService
    ) {
        this.groupStatsCapabilityService = groupStatsCapabilityService;
        this.groupsListCapabilityService = groupsListCapabilityService;
        this.shortLinksQueryCapabilityService = shortLinksQueryCapabilityService;
    }

    @PostMapping("/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query")
    public ShortLinksQueryResponse queryShortLinks(
            @RequestBody ShortLinksQueryRequest request,
            @RequestHeader(value = "X-Request-ID", required = false) String requestedId
    ) {
        return shortLinksQueryCapabilityService.query(request, AgentCapabilityRequestIds.resolve(requestedId));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-capabilities/v1/groups/list")
    public GroupsListResponse listGroups(
            @RequestBody GroupsListRequest request,
            @RequestHeader(value = "X-Request-ID", required = false) String requestedId
    ) {
        return groupsListCapabilityService.list(request, AgentCapabilityRequestIds.resolve(requestedId));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query")
    public GroupStatsQueryResponse queryGroupStats(
            @RequestBody GroupStatsQueryRequest request,
            @RequestHeader(value = "X-Request-ID", required = false) String requestedId
    ) {
        return groupStatsCapabilityService.query(request, AgentCapabilityRequestIds.resolve(requestedId));
    }
}
