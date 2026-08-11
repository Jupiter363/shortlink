package com.nageoffer.shortlink.admin.authority.capability.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;
import com.nageoffer.shortlink.admin.authority.capability.model.ShortLinksQueryRequest;
import com.nageoffer.shortlink.admin.authority.capability.model.ShortLinksQueryResponse;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.dao.entity.GroupDO;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
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
public class AgentShortLinksQueryCapabilityService {

    private static final String SCHEMA_VERSION = "1.0";

    private static final String SNAPSHOT_SOURCE = "admin/short-links";

    private static final long SNAPSHOT_TTL_SECONDS = 300L;

    private static final long MAX_PAGE_NUMBER = 10_000L;

    private static final long MAX_PAGE_SIZE = 500L;

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final int MAX_DESCRIPTION_LENGTH = 1_024;

    private static final Pattern GID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final Pattern SHORT_URL_PATTERN = Pattern.compile("^[^\\s\\p{Cntrl}]{1,2048}$");

    private final GroupService groupService;

    private final ShortLinkActualRemoteService shortLinkActualRemoteService;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public AgentShortLinksQueryCapabilityService(
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

    public ShortLinksQueryResponse query(ShortLinksQueryRequest request, String requestId) {
        ValidatedQuery query = validate(request);
        requireOwnedGroup(query.gid());

        Result<Page<ShortLinkPageRespDTO>> providerResult;
        try {
            providerResult = shortLinkActualRemoteService.pageShortLink(
                    query.gid(),
                    providerOrderTag(query.sort()),
                    query.current(),
                    query.size()
            );
        } catch (RuntimeException exception) {
            throw AgentCapabilityException.providerFailed(exception);
        }
        ShortLinksQueryResponse.Data data = normalize(query, requireProviderPage(providerResult));
        Instant observedAt = clock.instant();
        return new ShortLinksQueryResponse(
                SCHEMA_VERSION,
                requestId,
                new ShortLinksQueryResponse.Snapshot(
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

    private ValidatedQuery validate(ShortLinksQueryRequest request) {
        if (request == null
                || !GID_PATTERN.matcher(request.gid() == null ? "" : request.gid()).matches()
                || request.current() == null
                || request.current() < 1
                || request.current() > MAX_PAGE_NUMBER
                || request.size() == null
                || request.size() < 1
                || request.size() > MAX_PAGE_SIZE
                || request.sort() == null) {
            throw AgentCapabilityException.validation("Short-link query is invalid.");
        }
        return new ValidatedQuery(
                request.gid(),
                request.current(),
                request.size(),
                request.sort()
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

    private Page<ShortLinkPageRespDTO> requireProviderPage(Result<Page<ShortLinkPageRespDTO>> result) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw AgentCapabilityException.providerFailed();
        }
        return result.getData();
    }

    private ShortLinksQueryResponse.Data normalize(
            ValidatedQuery query,
            Page<ShortLinkPageRespDTO> page
    ) {
        if (page.getCurrent() != query.current()
                || page.getSize() != query.size()
                || page.getTotal() < 0
                || page.getTotal() > MAX_SAFE_INTEGER
                || page.getRecords() == null
                || page.getRecords().size() > query.size()
                || page.getTotal() < page.getRecords().size()) {
            throw AgentCapabilityException.providerFailed();
        }
        long expectedPages = page.getTotal() == 0
                ? 0
                : ((page.getTotal() - 1) / query.size()) + 1;
        if (page.getPages() != expectedPages
                || (query.current() > expectedPages && !page.getRecords().isEmpty())) {
            throw AgentCapabilityException.providerFailed();
        }

        List<ShortLinksQueryResponse.ShortLink> records = new ArrayList<>(page.getRecords().size());
        Set<String> fullShortUrls = new HashSet<>(page.getRecords().size());
        for (ShortLinkPageRespDTO providerRecord : page.getRecords()) {
            records.add(normalizeRecord(query.gid(), providerRecord, fullShortUrls));
        }
        return new ShortLinksQueryResponse.Data(
                query.gid(),
                query.current(),
                query.size(),
                page.getTotal(),
                expectedPages,
                query.current() < expectedPages,
                query.sort().name(),
                List.copyOf(records)
        );
    }

    private ShortLinksQueryResponse.ShortLink normalizeRecord(
            String expectedGid,
            ShortLinkPageRespDTO record,
            Set<String> fullShortUrls
    ) {
        if (record == null
                || !expectedGid.equals(record.getGid())
                || !SHORT_URL_PATTERN.matcher(
                        record.getFullShortUrl() == null ? "" : record.getFullShortUrl()
                ).matches()
                || !fullShortUrls.add(record.getFullShortUrl())
                || record.getCreateTime() == null
                || (record.getDescribe() != null && record.getDescribe().length() > MAX_DESCRIPTION_LENGTH)) {
            throw AgentCapabilityException.providerFailed();
        }

        Instant createdAt = record.getCreateTime().toInstant();
        String validity;
        Instant expiresAt;
        if (record.getValidDateType() == 0) {
            validity = "PERMANENT";
            expiresAt = null;
        } else if (record.getValidDateType() == 1 && record.getValidDate() != null) {
            validity = "CUSTOM";
            expiresAt = record.getValidDate().toInstant();
            if (!expiresAt.isAfter(createdAt)) {
                throw AgentCapabilityException.providerFailed();
            }
        } else {
            throw AgentCapabilityException.providerFailed();
        }
        return new ShortLinksQueryResponse.ShortLink(
                record.getFullShortUrl(),
                record.getDescribe(),
                validity,
                expiresAt,
                createdAt,
                metric(record.getTodayPv()),
                metric(record.getTodayUv()),
                metric(record.getTodayUip()),
                metric(record.getTotalPv()),
                metric(record.getTotalUv()),
                metric(record.getTotalUip())
        );
    }

    private int metric(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw AgentCapabilityException.providerFailed();
        }
        return value;
    }

    private String providerOrderTag(ShortLinksQueryRequest.Sort sort) {
        return switch (sort) {
            case CREATED_AT_DESC -> null;
            case TODAY_PV_DESC -> "todayPv";
            case TODAY_UV_DESC -> "todayUv";
            case TODAY_UIP_DESC -> "todayUip";
            case TOTAL_PV_DESC -> "totalPv";
            case TOTAL_UV_DESC -> "totalUv";
            case TOTAL_UIP_DESC -> "totalUip";
        };
    }

    private String contentHash(ShortLinksQueryResponse.Data data) {
        Map<String, Object> canonicalData = new TreeMap<>();
        canonicalData.put("current", data.current());
        canonicalData.put("gid", data.gid());
        canonicalData.put("hasNext", data.hasNext());
        canonicalData.put("pages", data.pages());
        canonicalData.put("records", data.records().stream().map(this::canonicalRecord).toList());
        canonicalData.put("size", data.size());
        canonicalData.put("sort", data.sort());
        canonicalData.put("total", data.total());
        try {
            byte[] canonicalJson = objectMapper.writeValueAsString(canonicalData)
                    .getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalJson)
            );
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw AgentCapabilityException.providerFailed(exception);
        }
    }

    private Map<String, Object> canonicalRecord(ShortLinksQueryResponse.ShortLink record) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("createdAt", record.createdAt().toString());
        canonical.put("describe", record.describe());
        canonical.put("expiresAt", record.expiresAt() == null ? null : record.expiresAt().toString());
        canonical.put("fullShortUrl", record.fullShortUrl());
        canonical.put("todayPv", record.todayPv());
        canonical.put("todayUip", record.todayUip());
        canonical.put("todayUv", record.todayUv());
        canonical.put("totalPv", record.totalPv());
        canonical.put("totalUip", record.totalUip());
        canonical.put("totalUv", record.totalUv());
        canonical.put("validity", record.validity());
        return canonical;
    }

    private String newSnapshotId() {
        return "snap-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record ValidatedQuery(
            String gid,
            long current,
            long size,
            ShortLinksQueryRequest.Sort sort
    ) {
    }
}
