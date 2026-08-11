package com.nageoffer.shortlink.admin.authority.capability.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupStatsQueryRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.GroupStatsQueryResponse;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.dao.entity.GroupDO;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentGroupStatsCapabilityService {

    private static final String SCHEMA_VERSION = "1.0";

    private static final String SNAPSHOT_SOURCE = "admin/group-stats";

    private static final long SNAPSHOT_TTL_SECONDS = 300L;

    private static final long MAX_RANGE_DAYS = 366L;

    private static final Pattern GID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final GroupService groupService;

    private final ShortLinkActualRemoteService shortLinkActualRemoteService;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public AgentGroupStatsCapabilityService(
            GroupService groupService,
            ShortLinkActualRemoteService shortLinkActualRemoteService,
            ObjectMapper objectMapper,
            @Qualifier("agentCapabilityClock") Clock clock
    ) {
        this.groupService = groupService;
        this.shortLinkActualRemoteService = shortLinkActualRemoteService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public GroupStatsQueryResponse query(GroupStatsQueryRequest request, String requestId) {
        ValidatedQuery query = validate(request);
        requireOwnedGroup(query.gid());

        Result<ShortLinkStatsRespDTO> providerResult = shortLinkActualRemoteService.groupShortLinkStats(
                query.gid(),
                query.startDate().toString(),
                query.endDateInclusive().toString()
        );
        ShortLinkStatsRespDTO stats = requireProviderData(providerResult);
        GroupStatsQueryResponse.Data data = new GroupStatsQueryResponse.Data(
                query.gid(),
                safeInt(stats.getPv()),
                safeInt(stats.getUv()),
                safeInt(stats.getUip())
        );
        Instant observedAt = clock.instant();
        return new GroupStatsQueryResponse(
                SCHEMA_VERSION,
                requestId,
                new GroupStatsQueryResponse.Snapshot(
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

    private ValidatedQuery validate(GroupStatsQueryRequest request) {
        if (request == null || !StringUtils.hasText(request.gid())
                || !GID_PATTERN.matcher(request.gid()).matches()) {
            throw AgentCapabilityException.validation("gid is invalid.");
        }
        GroupStatsQueryRequest.TimeRange timeRange = request.timeRange();
        if (timeRange == null || timeRange.start() == null || timeRange.end() == null
                || !StringUtils.hasText(timeRange.timezone())) {
            throw AgentCapabilityException.validation("timeRange is required.");
        }

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timeRange.timezone());
        } catch (DateTimeException ex) {
            throw AgentCapabilityException.validation("timeRange.timezone is invalid.");
        }
        ZonedDateTime start = timeRange.start().atZoneSameInstant(zoneId);
        ZonedDateTime end = timeRange.end().atZoneSameInstant(zoneId);
        if (!LocalTime.MIDNIGHT.equals(start.toLocalTime())
                || !LocalTime.MIDNIGHT.equals(end.toLocalTime())) {
            throw AgentCapabilityException.validation("timeRange must align to local day boundaries.");
        }
        long rangeDays = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
        if (rangeDays < 1 || rangeDays > MAX_RANGE_DAYS) {
            throw AgentCapabilityException.validation("timeRange must contain 1 to 366 days.");
        }
        return new ValidatedQuery(
                request.gid(),
                start.toLocalDate(),
                end.toLocalDate().minusDays(1)
        );
    }

    private void requireOwnedGroup(String gid) {
        String username = UserContext.getUsername();
        if (!StringUtils.hasText(username)) {
            throw AgentCapabilityException.forbidden();
        }
        Long groupCount = groupService.count(Wrappers.lambdaQuery(GroupDO.class)
                .eq(GroupDO::getUsername, username)
                .eq(GroupDO::getGid, gid)
                .eq(GroupDO::getDelFlag, 0));
        if (groupCount == null || groupCount < 1) {
            throw AgentCapabilityException.forbidden();
        }
    }

    private ShortLinkStatsRespDTO requireProviderData(Result<ShortLinkStatsRespDTO> result) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw AgentCapabilityException.providerFailed();
        }
        return result.getData();
    }

    private String contentHash(GroupStatsQueryResponse.Data data) {
        Map<String, Object> canonicalData = new TreeMap<>();
        canonicalData.put("gid", data.gid());
        canonicalData.put("pv", data.pv());
        canonicalData.put("uip", data.uip());
        canonicalData.put("uv", data.uv());
        try {
            byte[] canonicalJson = objectMapper.writeValueAsString(canonicalData)
                    .getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalJson)
            );
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw AgentCapabilityException.providerFailed();
        }
    }

    private String newSnapshotId() {
        return "snap-" + UUID.randomUUID().toString().replace("-", "");
    }

    private int safeInt(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw AgentCapabilityException.providerFailed();
        }
        return value;
    }

    private record ValidatedQuery(
            String gid,
            LocalDate startDate,
            LocalDate endDateInclusive
    ) {
    }
}
