package com.nageoffer.shortlink.admin.authority.capability.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupsListRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupsListResponse;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentGroupsListCapabilityService {

    private static final String SCHEMA_VERSION = "1.0";

    private static final String SNAPSHOT_SOURCE = "admin/groups";

    private static final long SNAPSHOT_TTL_SECONDS = 300L;

    private static final int MAX_GROUPS = 1_000;

    private static final int MAX_GROUP_NAME_LENGTH = 128;

    private static final Pattern GID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final GroupService groupService;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public AgentGroupsListCapabilityService(
            GroupService groupService,
            ObjectMapper objectMapper,
            @Qualifier("agentCapabilityClock") Clock clock
    ) {
        this.groupService = groupService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public GroupsListResponse list(GroupsListRequest request, String requestId) {
        if (request == null) {
            throw AgentCapabilityException.validation("Request body is required.");
        }
        if (!StringUtils.hasText(UserContext.getUsername())) {
            throw AgentCapabilityException.forbidden();
        }

        List<ShortLinkGroupRespDTO> providerGroups;
        try {
            providerGroups = groupService.listGroup();
        } catch (RuntimeException exception) {
            throw AgentCapabilityException.providerFailed(exception);
        }
        List<GroupsListResponse.Group> data = normalize(providerGroups);
        Instant observedAt = clock.instant();
        return new GroupsListResponse(
                SCHEMA_VERSION,
                requestId,
                new GroupsListResponse.Snapshot(
                        newSnapshotId(),
                        SNAPSHOT_SOURCE,
                        observedAt,
                        observedAt.plusSeconds(SNAPSHOT_TTL_SECONDS),
                        contentHash(data)
                ),
                data,
                List.of()
        );
    }

    private List<GroupsListResponse.Group> normalize(List<ShortLinkGroupRespDTO> providerGroups) {
        if (providerGroups == null || providerGroups.size() > MAX_GROUPS) {
            throw AgentCapabilityException.providerFailed();
        }
        List<GroupsListResponse.Group> normalized = new ArrayList<>(providerGroups.size());
        Set<String> gids = new HashSet<>(providerGroups.size());
        for (ShortLinkGroupRespDTO group : providerGroups) {
            if (group == null
                    || !GID_PATTERN.matcher(group.getGid() == null ? "" : group.getGid()).matches()
                    || !gids.add(group.getGid())
                    || !StringUtils.hasText(group.getName())
                    || group.getName().length() > MAX_GROUP_NAME_LENGTH
                    || group.getSortOrder() == null
                    || group.getShortLinkCount() == null
                    || group.getShortLinkCount() < 0) {
                throw AgentCapabilityException.providerFailed();
            }
            normalized.add(new GroupsListResponse.Group(
                    group.getGid(),
                    group.getName(),
                    group.getSortOrder(),
                    group.getShortLinkCount()
            ));
        }
        return List.copyOf(normalized);
    }

    private String contentHash(List<GroupsListResponse.Group> data) {
        List<Map<String, Object>> canonicalData = data.stream().map(group -> {
            Map<String, Object> canonicalGroup = new TreeMap<>();
            canonicalGroup.put("gid", group.gid());
            canonicalGroup.put("name", group.name());
            canonicalGroup.put("shortLinkCount", group.shortLinkCount());
            canonicalGroup.put("sortOrder", group.sortOrder());
            return canonicalGroup;
        }).toList();
        try {
            byte[] canonicalJson = objectMapper.writeValueAsString(canonicalData)
                    .getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalJson)
            );
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw AgentCapabilityException.providerFailed();
        }
    }

    private String newSnapshotId() {
        return "snap-" + UUID.randomUUID().toString().replace("-", "");
    }
}
