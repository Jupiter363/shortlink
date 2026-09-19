package com.jupiter.shortlink.command.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.risk.HostNormalizer;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@org.springframework.transaction.annotation.Transactional(
        readOnly = true,
        isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ResourceAuthorizationController {
    public record AnalyticsRequest(
            String tenantId, String subjectId, long authVersion, String gid, List<Long> linkIds) {}

    public record ResolveRequest(
            String gid,
            String fullShortUrl,
            List<Long> linkIds,
            Long afterLinkId,
            String ownershipVersion) {}

    public record LinkIdentity(
            long linkId,
            String gid,
            String domain,
            String shortUri,
            String fullShortUrl,
            long ownershipVersion) {}

    public record Scope(
            String tenantId, String ownershipVersion, List<LinkIdentity> links, Long nextCursor) {}

    public record AnalyticsScope(
            boolean allowed, String tenantId, List<Long> linkIds, String ownershipVersion) {}

    /** An explicit selection never falls back to the current contents of its group. */
    public record SelectedRequest(String gid, List<Long> linkIds, String ownershipVersion) {
        public SelectedRequest {
            if (gid == null || gid.isBlank() || gid.length() > 64
                    || gid.chars().anyMatch(c -> c < 32 || c == 127))
                throw new IllegalArgumentException("Invalid selected group");
            linkIds = FrozenQueryScope.validatedMembers(linkIds);
            if (ownershipVersion != null && !ownershipVersion.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid selected ownership version");
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SelectedRequest fromJson(Map<String, Object> values) {
            Set<String> fields = Set.of("gid", "linkIds", "ownershipVersion");
            if (values == null || !fields.containsAll(values.keySet())
                    || !values.keySet().containsAll(Set.of("gid", "linkIds")))
                throw new IllegalArgumentException("Invalid selected scope fields");
            return new SelectedRequest(selectedText(values.get("gid")), selectedMembers(values.get("linkIds")),
                    values.get("ownershipVersion") == null ? null : selectedText(values.get("ownershipVersion")));
        }
    }

    /** A strict wire DTO only for the new endpoint; the existing analytics contract is unchanged. */
    public record SelectedAnalyticsRequest(
            String tenantId, String subjectId, long authVersion, String gid, List<Long> linkIds) {
        public SelectedAnalyticsRequest {
            SelectedRequest selection = new SelectedRequest(gid, linkIds, null);
            linkIds = selection.linkIds();
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SelectedAnalyticsRequest fromJson(Map<String, Object> values) {
            if (values == null || !values.keySet().equals(Set.of("tenantId", "subjectId", "authVersion", "gid", "linkIds")))
                throw new IllegalArgumentException("Invalid selected analytics fields");
            return new SelectedAnalyticsRequest(selectedText(values.get("tenantId")), selectedText(values.get("subjectId")),
                    selectedInteger(values.get("authVersion")), selectedText(values.get("gid")),
                    selectedMembers(values.get("linkIds")));
        }
    }

    public record SelectedScope(
            String schemaVersion,
            boolean allowed,
            String tenantId,
            String subjectId,
            long authVersion,
            String gid,
            List<Long> linkIds,
            String memberHash,
            String ownershipVersion,
            List<LinkIdentity> links) {
        public SelectedScope {
            linkIds = List.copyOf(linkIds);
            links = List.copyOf(links);
        }
    }

    private final JdbcTemplate jdbc;
    private final CommandAuthorization auth;

    public ResourceAuthorizationController(JdbcTemplate jdbc, CommandAuthorization auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @PostMapping("/internal/v1/authorization/analytics")
    public AnalyticsScope analytics(@RequestBody AnalyticsRequest q, HttpServletRequest r) {
        auth.requireService(r);
        CommandPrincipal p =
                new CommandPrincipal(Long.parseLong(q.tenantId()), q.subjectId(), q.authVersion());
        auth.check(p, false);
        Scope scope = resolve(p, new ResolveRequest(q.gid(), null, q.linkIds(), null, null));
        if (scope.nextCursor() != null)
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Analytics scope requires bounded pagination");
        return new AnalyticsScope(
                true,
                scope.tenantId(),
                scope.links().stream().map(LinkIdentity::linkId).toList(),
                scope.ownershipVersion());
    }

    @PostMapping("/internal/command/authorization/resolve")
    public Scope resolve(@RequestBody ResolveRequest q, HttpServletRequest r) {
        return resolve(auth.principal(r), q);
    }

    @PostMapping("/internal/command/authorization/resolve-selected")
    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SelectedScope resolveSelected(@RequestBody SelectedRequest q, HttpServletRequest r) {
        return resolveSelected(auth.principal(r), q);
    }

    @PostMapping("/internal/v1/authorization/analytics-selected")
    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SelectedScope analyticsSelected(@RequestBody SelectedAnalyticsRequest q, HttpServletRequest r) {
        auth.requireService(r);
        CommandPrincipal principal;
        try {
            principal = new CommandPrincipal(Long.parseLong(q.tenantId()), q.subjectId(), q.authVersion());
        } catch (IllegalArgumentException invalid) {
            CommandAuthorization.denied();
            return null;
        }
        return resolveSelected(principal, new SelectedRequest(q.gid(), q.linkIds(), null));
    }

    private SelectedScope resolveSelected(CommandPrincipal principal, SelectedRequest request) {
        auth.check(principal, false);
        requireSelectedGroup(principal, request.gid());
        List<LinkIdentity> links = List.of();
        if (!request.linkIds().isEmpty()) {
            List<Object> parameters = new ArrayList<>();
            parameters.add(principal.tenantId());
            parameters.add(request.gid());
            parameters.addAll(request.linkIds());
            links = jdbc.query(
                    "SELECT link_id,current_gid,domain_norm,short_uri,ownership_version"
                            + " FROM t_link_route WHERE tenant_id=? AND current_gid=?"
                            + " AND route_status<>'DELETED' AND link_id IN ("
                            + String.join(",", Collections.nCopies(request.linkIds().size(), "?"))
                            + ") ORDER BY link_id",
                    (rs, n) -> new LinkIdentity(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), "https://" + rs.getString(3) + "/" + rs.getString(4),
                            rs.getLong(5)),
                    parameters.toArray());
        }
        if (!links.stream().map(LinkIdentity::linkId).toList().equals(request.linkIds()))
            CommandAuthorization.denied();

        // Group revisions include unrelated additions; only selected route ownership is pinned.
        StringBuilder version = new StringBuilder("selected-ownership/v1");
        appendSelectedIdentity(version, Long.toString(principal.tenantId()));
        appendSelectedIdentity(version, principal.username());
        appendSelectedIdentity(version, Long.toString(principal.authVersion()));
        appendSelectedIdentity(version, request.gid());
        for (LinkIdentity link : links) {
            appendSelectedIdentity(version, Long.toString(link.linkId()));
            appendSelectedIdentity(version, Long.toString(link.ownershipVersion()));
        }
        String ownership = digest(version.toString());
        if (request.ownershipVersion() != null && !request.ownershipVersion().equals(ownership))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected ownership changed");

        // New selected endpoints use READ_COMMITTED so these are current authorization checks.
        auth.check(principal, false);
        requireSelectedGroup(principal, request.gid());
        return new SelectedScope("selected-scope/v1", true, Long.toString(principal.tenantId()),
                principal.username(), principal.authVersion(), request.gid(), request.linkIds(),
                FrozenQueryScope.memberHash(request.linkIds()), ownership, links);
    }

    private void requireSelectedGroup(CommandPrincipal principal, String gid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_group WHERE username=? AND tenant_id=? AND gid=? AND del_flag=0",
                Integer.class, principal.username(), principal.tenantId(), gid);
        if (count == null || count != 1) CommandAuthorization.denied();
    }

    private static void appendSelectedIdentity(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    private static String selectedText(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Invalid selected scope text");
        return text;
    }

    private static List<Long> selectedMembers(Object value) {
        if (!(value instanceof List<?> values) || values.size() > FrozenQueryScope.SHARD_SIZE)
            throw new IllegalArgumentException("Invalid selected members");
        return values.stream().map(ResourceAuthorizationController::selectedInteger).toList();
    }

    private static long selectedInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger))
            throw new IllegalArgumentException("Selected scope requires integer values");
        try {
            long result = new java.math.BigInteger(value.toString()).longValueExact();
            if (result <= 0) throw new IllegalArgumentException("Selected scope requires positive integers");
            return result;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Selected scope integer overflow", invalid);
        }
    }

    public Scope resolve(CommandPrincipal p, ResolveRequest q) {
        auth.check(p, false);
        if (q.linkIds() != null && q.linkIds().size() > 500)
            throw new IllegalArgumentException("Scope limit is 500");
        if (q.gid() != null && (q.gid().isBlank() || q.gid().length() > 64))
            throw new IllegalArgumentException("Invalid group");
        if (q.gid() == null
                && q.fullShortUrl() == null
                && (q.linkIds() == null || q.linkIds().isEmpty()))
            throw new IllegalArgumentException("Resource scope required");
        StringBuilder sql =
                new StringBuilder(
                        "SELECT link_id,current_gid,domain_norm,short_uri,ownership_version FROM"
                                + " t_link_route WHERE tenant_id=? AND route_status<>'DELETED'");
        List<Object> params = new ArrayList<>();
        params.add(p.tenantId());
        if (q.gid() != null) {
            sql.append(" AND current_gid=?");
            params.add(q.gid());
        }
        if (q.fullShortUrl() != null) {
            URI uri =
                    URI.create(
                            q.fullShortUrl().contains("://")
                                    ? q.fullShortUrl()
                                    : "https://" + q.fullShortUrl());
            if (uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPath() == null
                    || !uri.getPath().matches("/[0-9A-Za-z]{9}"))
                throw new IllegalArgumentException("Invalid short-link address");
            String domain = new HostNormalizer().normalize(uri.getRawAuthority(), uri.getScheme());
            sql.append(" AND domain_norm=? AND short_uri=?");
            params.add(domain);
            params.add(uri.getPath().substring(1));
        }
        if (q.linkIds() != null && !q.linkIds().isEmpty()) {
            sql.append(" AND link_id IN (")
                    .append(String.join(",", Collections.nCopies(q.linkIds().size(), "?")))
                    .append(")");
            params.addAll(q.linkIds());
        }
        if (q.afterLinkId() != null) {
            if (q.afterLinkId() < 0 || q.ownershipVersion() == null || q.gid() == null)
                throw new IllegalArgumentException("Cursor requires frozen group ownership");
            sql.append(" AND link_id>?");
            params.add(q.afterLinkId());
        }
        sql.append(" ORDER BY link_id LIMIT 501");
        List<LinkIdentity> links =
                jdbc.query(
                        sql.toString(),
                        (rs, n) ->
                                new LinkIdentity(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        "https://" + rs.getString(3) + "/" + rs.getString(4),
                                        rs.getLong(5)),
                        params.toArray());
        Set<String> gids = new TreeSet<>();
        if (q.gid() != null) gids.add(q.gid());
        for (var link : links) gids.add(link.gid());
        StringBuilder version = new StringBuilder(p.tenantId() + ":" + p.authVersion());
        for (String gid : gids) {
            var groups =
                    jdbc.queryForList(
                            "SELECT revision FROM t_group WHERE username=? AND tenant_id=? AND"
                                    + " gid=? AND del_flag=0",
                            p.username(),
                            p.tenantId(),
                            gid);
            if (groups.size() != 1) CommandAuthorization.denied();
            version.append('|').append(gid).append(':').append(groups.get(0).get("revision"));
        }
        if (q.gid() == null)
            for (var link : links)
                version.append('|')
                        .append(link.linkId())
                        .append(':')
                        .append(link.ownershipVersion());
        if (q.fullShortUrl() != null && links.size() != 1) CommandAuthorization.denied();
        if (q.linkIds() != null
                && !q.linkIds().isEmpty()
                && links.size() != new HashSet<>(q.linkIds()).size()) CommandAuthorization.denied();
        String ownership = digest(version.toString());
        if (q.ownershipVersion() != null && !q.ownershipVersion().equals(ownership))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ownership cursor expired");
        boolean more = links.size() > 500;
        List<LinkIdentity> page = more ? List.copyOf(links.subList(0, 500)) : List.copyOf(links);
        auth.check(p, false);
        return new Scope(
                Long.toString(p.tenantId()),
                ownership,
                page,
                more ? page.get(page.size() - 1).linkId() : null);
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
