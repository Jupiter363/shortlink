package com.jupiter.shortlink.command.api;

import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.security.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@RestController
public class LinkCommandController {
    private static final DateTimeFormatter MANAGEMENT_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final LinkCommandService links;
    private final GroupCommandService groups;
    private final CommandAuthorization auth;
    private final JdbcTemplate jdbc;
    private final String defaultDomain;

    public LinkCommandController(
            LinkCommandService links,
            GroupCommandService groups,
            CommandAuthorization auth,
            JdbcTemplate jdbc,
            @Value("${shortlink.default-domain}") String domain) {
        this.links = links;
        this.groups = groups;
        this.auth = auth;
        this.jdbc = jdbc;
        this.defaultDomain = domain;
    }

    public record Result<T>(String code, T data) {
        public boolean isSuccess() {
            return code.equals("0");
        }
    }

    public record Create(
            String requestId,
            String domain,
            String originUrl,
            String gid,
            int createdType,
            int validDateType,
            @com.fasterxml.jackson.annotation.JsonFormat(
                            pattern = "yyyy-MM-dd HH:mm:ss",
                            timezone = "GMT+8")
                    Date validDate,
            String describe) {}

    public record Update(
            String fullShortUrl,
            String originGid,
            String gid,
            String originUrl,
            int validDateType,
            @com.fasterxml.jackson.annotation.JsonFormat(
                            pattern = "yyyy-MM-dd HH:mm:ss",
                            timezone = "GMT+8")
                    Date validDate,
            String describe,
            Long expectedVersion) {}

    public record Lifecycle(String fullShortUrl, String gid, Long expectedVersion) {}

    @PostMapping("/api/short-link/v1/create")
    public CompletableFuture<Result<LinkCommandService.Created>> create(
            @RequestBody Create q, HttpServletRequest r) {
        var p = auth.principal(r);
        String requestId = q.requestId() == null ? r.getHeader("Idempotency-Key") : q.requestId();
        var c =
                new LinkCommandService.Creation(
                        q.domain() == null ? defaultDomain : q.domain(),
                        q.originUrl(),
                        q.gid(),
                        q.createdType(),
                        q.validDateType(),
                        q.validDate() == null ? null : q.validDate().getTime(),
                        q.describe());
        return com.jupiter.shortlink.command.membership.RoutePublication.map(
                links.createManyAsync(p, requestId, List.of(c)),
                created -> new Result<>("0", created.get(0)));
    }

    @PostMapping("/api/short-link/v1/update")
    public Result<Void> update(@RequestBody Update q, HttpServletRequest r) {
        var p = auth.principal(r);
        var route = links.byAddress(p, q.fullShortUrl());
        if (q.expectedVersion() == null)
            throw new IllegalArgumentException("expectedVersion required");
        if (q.originGid() != null && !route.gid().equals(q.originGid()))
            throw new IllegalArgumentException("Source group changed");
        links.update(
                p,
                route.linkId(),
                q.expectedVersion(),
                q.gid(),
                q.originUrl(),
                q.validDateType() == 0
                        ? null
                        : Objects.requireNonNull(q.validDate(), "validDate required").getTime(),
                q.describe());
        return new Result<>("0", null);
    }

    @PostMapping("/api/short-link/v1/recycle-bin/save")
    public Result<Void> recycle(@RequestBody Lifecycle q, HttpServletRequest r) {
        return transition(q, r, "DISABLED");
    }

    @PostMapping("/api/short-link/v1/recycle-bin/recover")
    public Result<Void> recover(@RequestBody Lifecycle q, HttpServletRequest r) {
        return transition(q, r, "ACTIVE");
    }

    @PostMapping("/api/short-link/v1/recycle-bin/remove")
    public Result<Void> delete(@RequestBody Lifecycle q, HttpServletRequest r) {
        return transition(q, r, "DELETED");
    }

    private Result<Void> transition(Lifecycle q, HttpServletRequest r, String state) {
        var p = auth.principal(r);
        var route = links.byAddress(p, q.fullShortUrl());
        if (!route.gid().equals(q.gid())) CommandAuthorization.denied();
        if (q.expectedVersion() == null)
            throw new IllegalArgumentException("expectedVersion required");
        links.transition(p, route.linkId(), q.expectedVersion(), state);
        return new Result<>("0", null);
    }

    @GetMapping("/api/short-link/v1/count")
    public Result<List<Map<String, Object>>> count(
            @RequestParam("requestParam") List<String> gids, HttpServletRequest r) {
        var p = auth.principal(r);
        Set<String> owned = new HashSet<>();
        groups.list(p).forEach(g -> owned.add(g.gid()));
        if (gids.size() > 20 || !owned.containsAll(gids)) CommandAuthorization.denied();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String gid : gids) {
            long count =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM t_link_route WHERE tenant_id=? AND current_gid=?"
                                    + " AND route_status='ACTIVE'",
                            Long.class,
                            p.tenantId(),
                            gid);
            result.add(Map.of("gid", gid, "shortLinkCount", count));
        }
        return new Result<>("0", result);
    }

    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @GetMapping("/api/short-link/v1/page")
    public Result<Map<String, Object>> page(
            @RequestParam String gid,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String orderTag,
            HttpServletRequest r) {
        if (orderTag != null && !orderTag.isBlank() && !orderTag.equals("createTime"))
            throw new IllegalArgumentException(
                    "Statistics ordering requires an Analytics snapshot");
        return pageGroups(auth.principal(r), List.of(gid), current, size, false);
    }

    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @GetMapping("/api/short-link/v1/recycle-bin/page")
    public Result<Map<String, Object>> recyclePage(
            @RequestParam List<String> gidList,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest r) {
        return pageGroups(auth.principal(r), gidList, current, size, true);
    }

    private Result<Map<String, Object>> pageGroups(
            CommandPrincipal p, List<String> gids, long current, int size, boolean recycled) {
        if (size < 1
                || size > 500
                || current < 1
                || current > 10000 / size + 1
                || gids == null
                || gids.isEmpty()
                || gids.size() > 20)
            throw new IllegalArgumentException("Pagination exceeds query budget");
        Set<String> owned = new HashSet<>();
        groups.list(p).forEach(g -> owned.add(g.gid()));
        if (!owned.containsAll(gids)) CommandAuthorization.denied();
        String marks = String.join(",", Collections.nCopies(gids.size(), "?"));
        List<Object> args =
                new ArrayList<>(List.of(p.tenantId(), recycled ? "DISABLED" : "ACTIVE"));
        args.addAll(gids);
        String where = " WHERE tenant_id=? AND route_status=? AND current_gid IN (" + marks + ")";
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_link_route" + where, Long.class, args.toArray());
        args.add(size);
        args.add((current - 1) * size);
        List<Map<String, Object>> records =
                jdbc.query(
                        "SELECT * FROM t_link_route"
                                + where
                                + " ORDER BY created_at DESC,link_id DESC LIMIT ? OFFSET ?",
                        (rs, n) -> {
                            Map<String, Object> v = new LinkedHashMap<>();
                            String gid = rs.getString("current_gid");
                            long id = rs.getLong("link_id");
                            v.put("id", id);
                            v.put("linkId", id);
                            v.put("gid", gid);
                            v.put("domain", "https://" + rs.getString("domain_norm"));
                            v.put("shortUri", rs.getString("short_uri"));
                            v.put(
                                    "fullShortUrl",
                                    rs.getString("domain_norm") + "/" + rs.getString("short_uri"));
                            v.put("originUrl", rs.getString("origin_url"));
                            v.put("routeVersion", rs.getLong("route_version"));
                            v.put("ownershipVersion", rs.getLong("ownership_version"));
                            v.put("validDate", managementDateTime(rs.getTimestamp("expire_at")));
                            v.put("validDateType", rs.getTimestamp("expire_at") == null ? 0 : 1);
                            v.put("enableStatus", recycled ? 1 : 0);
                            v.put("metadataStatus", rs.getString("metadata_status"));
                            return v;
                        },
                        args.toArray());
        // Detail reads use the current gid and tenant on the same business authority; counters are
        // served by Analytics API.
        Map<Long, Map<String, Object>> detailById = new HashMap<>();
        for (String gid : new LinkedHashSet<>(gids)) {
            List<Long> ids =
                    records.stream()
                            .filter(v -> gid.equals(v.get("gid")))
                            .map(v -> ((Number) v.get("linkId")).longValue())
                            .toList();
            if (ids.isEmpty()) continue;
            List<Object> detailArgs = new ArrayList<>(List.of(gid, p.tenantId()));
            detailArgs.addAll(ids);
            for (var detail :
                    jdbc.queryForList(
                            "SELECT id,describe_text,title,favicon,create_time FROM t_link WHERE"
                                    + " gid=? AND tenant_id=? AND id IN ("
                                    + String.join(",", Collections.nCopies(ids.size(), "?"))
                                    + ")",
                            detailArgs.toArray()))
                detailById.put(((Number) detail.get("id")).longValue(), detail);
        }
        for (Map<String, Object> record : records) {
            var detail = detailById.get(((Number) record.get("linkId")).longValue());
            if (detail == null) throw new IllegalStateException("Route/detail inconsistency");
            record.put("describe", detail.get("describe_text"));
            record.put("title", detail.get("title"));
            record.put("favicon", detail.get("favicon"));
            record.put("createTime", managementDateTime(detail.get("create_time")));
        }
        return new Result<>(
                "0",
                Map.of(
                        "records",
                        records,
                        "current",
                        current,
                        "size",
                        size,
                        "total",
                        total == null ? 0 : total));
    }

    static String managementDateTime(Object value) {
        if (value == null) return null;
        LocalDateTime dateTime;
        if (value instanceof Timestamp timestamp) dateTime = timestamp.toLocalDateTime();
        else if (value instanceof LocalDateTime localDateTime) dateTime = localDateTime;
        else throw new IllegalStateException("Unsupported management date value");
        return MANAGEMENT_DATE_TIME.format(dateTime);
    }
}
