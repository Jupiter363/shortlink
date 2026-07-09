package com.nageoffer.shortlink.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.dao.entity.GroupDO;
import com.nageoffer.shortlink.admin.dto.resp.AgentRiskActiveShortLinkRespDTO;
import com.nageoffer.shortlink.admin.dto.resp.AgentRiskShortLinkStatsWindowRespDTO;
import com.nageoffer.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkGroupStatsAccessRecordReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkGroupStatsReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkPageReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkStatsReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsAccessRecordRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsBrowserRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsDeviceRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsLocaleCNRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsTopIpRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@RestController
@RequiredArgsConstructor
public class AgentToolInternalController {

    private static final long RISK_PAGE_SIZE = 500L;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter PROJECT_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GroupService groupService;

    private final ShortLinkActualRemoteService shortLinkActualRemoteService;

    @GetMapping("/internal/short-link-admin/v1/agent-tools/groups")
    public Result<List<ShortLinkGroupRespDTO>> listGroups() {
        return Results.success(groupService.listGroup());
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/short-links/page")
    public Result<Page<ShortLinkPageRespDTO>> pageShortLinks(ShortLinkPageReqDTO requestParam) {
        requireOwnedGid(requestParam.getGid());
        return shortLinkActualRemoteService.pageShortLink(
                requestParam.getGid(),
                requestParam.getOrderTag(),
                requestParam.getCurrent(),
                requestParam.getSize()
        );
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/short-link/stats")
    public Result<ShortLinkStatsRespDTO> shortLinkStats(ShortLinkStatsReqDTO requestParam) {
        requireOwnedGid(requestParam.getGid());
        return shortLinkActualRemoteService.oneShortLinkStats(
                requestParam.getFullShortUrl(),
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate()
        );
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/group/stats")
    public Result<ShortLinkStatsRespDTO> groupStats(ShortLinkGroupStatsReqDTO requestParam) {
        requireOwnedGid(requestParam.getGid());
        return shortLinkActualRemoteService.groupShortLinkStats(
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate()
        );
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/group/access-records")
    public Result<Page<ShortLinkStatsAccessRecordRespDTO>> groupAccessRecords(
            ShortLinkGroupStatsAccessRecordReqDTO requestParam) {
        requireOwnedGid(requestParam.getGid());
        return shortLinkActualRemoteService.groupShortLinkStatsAccessRecord(
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate(),
                requestParam.getCurrent(),
                requestParam.getSize()
        );
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/risk/active-short-links")
    public Result<List<AgentRiskActiveShortLinkRespDTO>> riskActiveShortLinks(@RequestParam("since") String since) {
        LocalDateTime sinceTime = parseDateTime(since);
        String startTime = formatProjectDateTime(sinceTime);
        String endTime = formatProjectDateTime(sinceTime.plusDays(7));
        List<AgentRiskActiveShortLinkRespDTO> activeLinks = new ArrayList<>();
        for (ShortLinkGroupRespDTO group : safeList(groupService.listGroup())) {
            String gid = group.getGid();
            long current = 1L;
            while (true) {
                Page<ShortLinkPageRespDTO> page = data(shortLinkActualRemoteService.pageShortLink(
                        gid,
                        "totalPv",
                        current,
                        RISK_PAGE_SIZE
                ));
                List<ShortLinkPageRespDTO> records = pageRecords(page);
                for (ShortLinkPageRespDTO link : records) {
                    if (safeInt(link.getTotalPv()) < 1 && safeInt(link.getTodayPv()) < 1) {
                        continue;
                    }
                    ShortLinkStatsRespDTO stats = data(shortLinkActualRemoteService.oneShortLinkStats(
                            link.getFullShortUrl(),
                            gid,
                            startTime,
                            endTime
                    ));
                    if (stats == null || safeInt(stats.getPv()) < 1) {
                        continue;
                    }
                    activeLinks.add(AgentRiskActiveShortLinkRespDTO.builder()
                            .gid(gid)
                            .domain(resolveDomain(link))
                            .shortUri(link.getShortUri())
                            .fullShortUrl(link.getFullShortUrl())
                            .pv(stats.getPv())
                            .uv(stats.getUv())
                            .uip(stats.getUip())
                            .build());
                }
                if (page == null || records.isEmpty() || current >= page.getPages()) {
                    break;
                }
                current++;
            }
        }
        return Results.success(activeLinks);
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats")
    public Result<AgentRiskShortLinkStatsWindowRespDTO> riskShortLinkWindowStats(
            @RequestParam("gid") String gid,
            @RequestParam("fullShortUrl") String fullShortUrl,
            @RequestParam("startTime") String startTime,
            @RequestParam("endTime") String endTime) {
        requireOwnedGid(gid);
        LocalDateTime windowStart = parseDateTime(startTime);
        LocalDateTime windowEnd = parseDateTime(endTime);
        if (!windowEnd.isAfter(windowStart)) {
            throw new ClientException("Risk stats endTime must be after startTime");
        }
        ShortLinkStatsRespDTO stats = data(shortLinkActualRemoteService.oneShortLinkStats(
                fullShortUrl,
                gid,
                formatProjectDateTime(windowStart),
                formatProjectDateTime(windowEnd)
        ));
        if (stats == null) {
            stats = new ShortLinkStatsRespDTO();
        }
        int pv = safeInt(stats.getPv());
        return Results.success(AgentRiskShortLinkStatsWindowRespDTO.builder()
                .gid(gid)
                .domain(domainOf(fullShortUrl))
                .shortUri(shortUriOf(fullShortUrl))
                .fullShortUrl(fullShortUrl)
                .startTime(startTime)
                .endTime(endTime)
                .pv(stats.getPv())
                .uv(stats.getUv())
                .uip(stats.getUip())
                .topIpShare(maxTopIpShare(stats.getTopIpStats(), pv))
                .topVisitorShare(null)
                .topRegionShare(maxRatio(stats.getLocaleCnStats(), pv))
                .topDeviceShare(maxDeviceRatio(stats.getDeviceStats(), pv))
                .topBrowserShare(maxBrowserRatio(stats.getBrowserStats(), pv))
                .peakHourShare(maxHourShare(stats.getHourStats(), pv, windowStart, windowEnd))
                .repeatVisitRatio(null)
                .build());
    }

    private void requireOwnedGid(String gid) {
        Long groupCount = groupService.count(Wrappers.lambdaQuery(GroupDO.class)
                .eq(GroupDO::getUsername, UserContext.getUsername())
                .eq(GroupDO::getGid, gid)
                .eq(GroupDO::getDelFlag, 0));
        if (groupCount == null || groupCount < 1) {
            throw new ClientException("Agent tool request gid is not owned by current user");
        }
    }

    private <T> T data(Result<T> result) {
        if (result == null || !result.isSuccess()) {
            return null;
        }
        return result.getData();
    }

    private List<ShortLinkPageRespDTO> pageRecords(Page<ShortLinkPageRespDTO> page) {
        if (page == null || page.getRecords() == null) {
            return List.of();
        }
        return page.getRecords();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(BUSINESS_ZONE)
                    .toLocalDateTime();
        }
    }

    private String formatProjectDateTime(LocalDateTime value) {
        return PROJECT_DATE_TIME_FORMATTER.format(value);
    }

    private String resolveDomain(ShortLinkPageRespDTO link) {
        if (link.getDomain() != null && !link.getDomain().isBlank()) {
            return link.getDomain();
        }
        return domainOf(link.getFullShortUrl());
    }

    private String domainOf(String fullShortUrl) {
        if (fullShortUrl == null) {
            return "";
        }
        int slashIndex = fullShortUrl.indexOf('/');
        return slashIndex < 0 ? fullShortUrl : fullShortUrl.substring(0, slashIndex);
    }

    private String shortUriOf(String fullShortUrl) {
        if (fullShortUrl == null) {
            return "";
        }
        int slashIndex = fullShortUrl.indexOf('/');
        return slashIndex < 0 ? "" : fullShortUrl.substring(slashIndex + 1);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private Double maxTopIpShare(List<ShortLinkStatsTopIpRespDTO> topIpStats, int pv) {
        if (pv < 1 || topIpStats == null || topIpStats.isEmpty()) {
            return null;
        }
        Integer maxCount = topIpStats.stream()
                .map(ShortLinkStatsTopIpRespDTO::getCnt)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
        return maxCount == null ? null : ratio(maxCount, pv);
    }

    private Double maxRatio(List<ShortLinkStatsLocaleCNRespDTO> rows, int pv) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .map(row -> ratioOrCount(row.getRatio(), row.getCnt(), pv))
                .filter(Objects::nonNull)
                .max(Double::compareTo)
                .orElse(null);
    }

    private Double maxDeviceRatio(List<ShortLinkStatsDeviceRespDTO> rows, int pv) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .map(row -> ratioOrCount(row.getRatio(), row.getCnt(), pv))
                .filter(Objects::nonNull)
                .max(Double::compareTo)
                .orElse(null);
    }

    private Double maxBrowserRatio(List<ShortLinkStatsBrowserRespDTO> rows, int pv) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .map(row -> ratioOrCount(row.getRatio(), row.getCnt(), pv))
                .filter(Objects::nonNull)
                .max(Double::compareTo)
                .orElse(null);
    }

    private Double ratioOrCount(Double ratio, Integer count, int pv) {
        if (ratio != null) {
            return ratio;
        }
        if (count == null || pv < 1) {
            return null;
        }
        return ratio(count, pv);
    }

    private Double maxHourShare(List<Integer> hourStats, int pv, LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (pv < 1 || hourStats == null || hourStats.isEmpty()) {
            return null;
        }
        List<Integer> selectedHours = hourStats;
        if (windowStart.toLocalDate().equals(windowEnd.toLocalDate())) {
            int startHour = Math.max(0, Math.min(23, windowStart.getHour()));
            int endHourExclusive = windowEnd.getHour();
            if (windowEnd.getMinute() > 0 || windowEnd.getSecond() > 0 || windowEnd.getNano() > 0) {
                endHourExclusive++;
            }
            endHourExclusive = Math.max(startHour + 1, Math.min(24, endHourExclusive));
            selectedHours = hourStats.subList(
                    Math.min(startHour, hourStats.size()),
                    Math.min(endHourExclusive, hourStats.size())
            );
        }
        if (selectedHours.isEmpty()) {
            return null;
        }
        Integer maxHourPv = Collections.max(selectedHours);
        return ratio(maxHourPv, pv);
    }

    private Double ratio(int numerator, int denominator) {
        if (denominator < 1) {
            return null;
        }
        return Math.min(1D, Math.max(0D, numerator * 1D / denominator));
    }
}
