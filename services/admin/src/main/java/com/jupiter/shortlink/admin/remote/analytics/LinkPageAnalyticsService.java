package com.jupiter.shortlink.admin.remote.analytics;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.remote.dto.req.ShortLinkPageReqDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * Business identities remain authoritative; statistics are joined from an authorized immutable
 * snapshot.
 */
@Service
public class LinkPageAnalyticsService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> ORDERS =
            Set.of("todayPv", "todayUv", "todayUip", "totalPv", "totalUv", "totalUip");
    private final ShortLinkActualRemoteService links;
    private final AgentAnalyticsFacade analytics;

    public LinkPageAnalyticsService(
            ShortLinkActualRemoteService links, AgentAnalyticsFacade analytics) {
        this.links = links;
        this.analytics = analytics;
    }

    public Result<Page<ShortLinkPageRespDTO>> page(ShortLinkPageReqDTO request) {
        long current = request.getCurrent(), size = request.getSize();
        if (current < 1 || size < 1 || size > 500 || current > 10_000 / size + 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid link page budget");
        String order = request.getOrderTag();
        boolean ranked = ORDERS.contains(Objects.toString(order, ""));
        if (!ranked && order != null && !order.isBlank() && !order.equals("createTime"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown link ordering");
        Result<Page<ShortLinkPageRespDTO>> result =
                links.pageShortLink(
                        request.getGid(), "createTime", ranked ? 1 : current, ranked ? 500 : size);
        if (result == null || !result.isSuccess() || result.getData() == null)
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Link authority unavailable");
        Page<ShortLinkPageRespDTO> source = result.getData();
        if (ranked && source.getTotal() > 500)
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TOO_LARGE: statistics ranking requires a scope of at most 500 links");
        List<ShortLinkPageRespDTO> rows = new ArrayList<>(source.getRecords());
        Map<String, Object> metadata = new LinkedHashMap<>();
        Instant end =
                request.getStatsEnd() == null
                        ? Instant.now()
                        : Instant.ofEpochMilli(request.getStatsEnd());
        if (request.getStatsSnapshotId() != null && request.getStatsEnd() == null)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Statistics continuation requires statsEnd");
        boolean lifetime = ranked && order.startsWith("total");
        Instant start =
                lifetime
                        ? rows.stream()
                                .map(ShortLinkPageRespDTO::getCreateTime)
                                .filter(Objects::nonNull)
                                .map(Date::toInstant)
                                .min(Comparator.naturalOrder())
                                .orElse(end)
                        : end.atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
        metadata.put("totalStatus", "LIFECYCLE_COVERAGE_UNKNOWN");
        if (lifetime
                && (rows.stream().anyMatch(r -> r.getCreateTime() == null)
                        || Duration.between(start, end).toDays() >= 7))
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ASYNC_REQUIRED: lifetime exceeds the synchronous statistics budget");
        if (!rows.isEmpty()) {
            try {
                StatsEnvelope stats =
                        analytics.query(
                                request.getGid(),
                                null,
                                start.toString(),
                                end.toString(),
                                null,
                                request.getStatsSnapshotId(),
                                null,
                                500,
                                "METRICS",
                                rows.stream().map(ShortLinkPageRespDTO::getLinkId).toList());
                metadata.putAll(stats.meta());
                metadata.put("statsEnd", end.toEpochMilli());
                boolean complete = "COMPLETE".equals(stats.meta().get("completeness"));
                if (lifetime && !complete)
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "Lifetime coverage is not complete");
                Map<Long, Map<String, Object>> byId = new HashMap<>();
                for (var row : stats.items()) byId.put(number(row.get("linkId")), row);
                for (var row : rows) {
                    var values = byId.get(row.getLinkId());
                    if (values == null)
                        throw new IllegalStateException("Statistics omitted an authorized link");
                    if (lifetime) {
                        row.setTotalPv(number(values.get("pv")));
                        row.setTotalUv(number(values.get("uv")));
                        row.setTotalUip(number(values.get("uip")));
                    } else {
                        row.setTodayPv(number(values.get("pv")));
                        row.setTodayUv(number(values.get("uv")));
                        row.setTodayUip(number(values.get("uip")));
                    }
                }
                if (lifetime) metadata.put("totalStatus", "COVERED");
            } catch (RuntimeException unavailable) {
                if (ranked || request.getStatsSnapshotId() != null)
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Statistics snapshot unavailable",
                            unavailable);
                metadata.put("availability", "UNAVAILABLE");
                rows.forEach(
                        row -> {
                            row.setTodayPv(null);
                            row.setTodayUv(null);
                            row.setTodayUip(null);
                        });
            }
        }
        if (ranked) {
            Function<ShortLinkPageRespDTO, Long> metric =
                    switch (order) {
                        case "todayPv" -> ShortLinkPageRespDTO::getTodayPv;
                        case "todayUv" -> ShortLinkPageRespDTO::getTodayUv;
                        case "todayUip" -> ShortLinkPageRespDTO::getTodayUip;
                        case "totalPv" -> ShortLinkPageRespDTO::getTotalPv;
                        case "totalUv" -> ShortLinkPageRespDTO::getTotalUv;
                        default -> ShortLinkPageRespDTO::getTotalUip;
                    };
            rows.sort(
                    Comparator.comparing(metric, Comparator.reverseOrder())
                            .thenComparing(ShortLinkPageRespDTO::getLinkId));
            int first = (int) Math.min(rows.size(), (current - 1) * size);
            rows = new ArrayList<>(rows.subList(first, (int) Math.min(rows.size(), first + size)));
        }
        AnalyticsPage page = new AnalyticsPage(current, size, source.getTotal(), metadata);
        page.setRecords(rows);
        return Results.success(page);
    }

    private static long number(Object value) {
        if (value == null) throw new IllegalStateException("Missing statistics metric");
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }

    public static final class AnalyticsPage extends Page<ShortLinkPageRespDTO> {
        private final Map<String, Object> statsMeta;

        public AnalyticsPage(long current, long size, long total, Map<String, Object> meta) {
            super(current, size, total);
            this.statsMeta = Collections.unmodifiableMap(new LinkedHashMap<>(meta));
        }

        public Map<String, Object> getStatsMeta() {
            return statsMeta;
        }
    }
}
