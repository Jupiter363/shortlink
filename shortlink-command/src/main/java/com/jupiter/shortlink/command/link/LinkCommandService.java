package com.jupiter.shortlink.command.link;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.batch.TenantQuotaService;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.command.security.*;
import com.jupiter.shortlink.contract.RouteChangeV1;
import com.jupiter.shortlink.contract.Topics;
import com.jupiter.shortlink.id.*;
import com.jupiter.shortlink.risk.HostNormalizer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.*;

@Service
public class LinkCommandService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommandAuthorization auth;
    private final GroupCommandService groups;
    private final IdGenerator ids;
    private final ShortCodeCodec codec;
    private final BusinessOutbox outbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final Set<String> allowedDomains;
    private final TenantQuotaService quota;

    public LinkCommandService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            CommandAuthorization auth,
            GroupCommandService groups,
            IdGenerator ids,
            ShortCodeCodec codec,
            BusinessOutbox outbox,
            ObjectMapper json,
            Clock clock,
            TenantQuotaService quota,
            @Value("${shortlink.domains}") String domains) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.auth = auth;
        this.groups = groups;
        this.ids = ids;
        this.codec = codec;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.quota = quota;
        this.tx.setTimeout(10);
        this.allowedDomains = new HashSet<>();
        for (String domain : domains.split(","))
            allowedDomains.add(new HostNormalizer().normalize(domain.trim(), "https"));
        if (allowedDomains.isEmpty())
            throw new IllegalArgumentException("At least one public domain required");
    }

    public record Creation(
            String domain,
            String originUrl,
            String gid,
            int createdType,
            int validDateType,
            Long expireAt,
            String describe) {}

    public record Created(
            long linkId,
            String fullShortUrl,
            String originUrl,
            String gid,
            String shortUri,
            long routeVersion,
            long targetRevision) {}

    public record Route(
            long linkId,
            long tenantId,
            String gid,
            String domain,
            String shortUri,
            String originUrl,
            String state,
            Long expireAt,
            long version,
            long ownershipVersion,
            long targetRevision) {}

    private Route row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        Timestamp e = r.getTimestamp("expire_at");
        return new Route(
                r.getLong("link_id"),
                r.getLong("tenant_id"),
                r.getString("current_gid"),
                r.getString("domain_norm"),
                r.getString("short_uri"),
                r.getString("origin_url"),
                r.getString("route_status"),
                e == null ? null : e.getTime(),
                r.getLong("route_version"),
                r.getLong("ownership_version"),
                r.getLong("target_revision"));
    }

    public List<Created> createMany(CommandPrincipal p, String requestId, List<Creation> input) {
        requireId(requestId);
        if (input == null || input.isEmpty() || input.size() > 500)
            throw new IllegalArgumentException("Synchronous batch size must be 1..500");
        List<Creation> frozen = List.copyOf(input);
        String digest = digest(frozen);
        auth.check(p, false);
        List<Created> committed = previousCreate(p, requestId, digest);
        if (committed != null) return committed;
        for (Creation c : frozen) validateCreation(c);
        List<IdRange> reserved = ids.reserveRanges(frozen.size());
        return tx.execute(
                s -> {
                    auth.check(p, true);
                    List<Created> previous = previousCreate(p, requestId, digest);
                    if (previous != null) return previous;
                    quota.lock(p.tenantId());
                    quota.consume(p.tenantId(), frozen.size());
                    List<Created> result = insertReservedMany(p, frozen, reserved);
                    jdbc.update(
                            "INSERT INTO"
                                + " t_command_result(tenant_id,command_id,request_digest,result_json,created_at)"
                                + " VALUES (?,?,?,?,?)",
                            p.tenantId(),
                            "create:" + requestId,
                            digest,
                            serialize(result),
                            clock.millis());
                    return List.copyOf(result);
                });
    }

    private List<Created> previousCreate(CommandPrincipal p, String requestId, String digest) {
        var previous =
                jdbc.queryForList(
                        "SELECT request_digest,result_json FROM t_command_result WHERE tenant_id=?"
                                + " AND command_id=?",
                        p.tenantId(),
                        "create:" + requestId);
        if (previous.isEmpty()) return null;
        if (!digest.equals(previous.get(0).get("request_digest")))
            conflict("Request ID already used for different input");
        try {
            return json.readValue(
                    (String) previous.get(0).get("result_json"),
                    new TypeReference<List<Created>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Stored result corrupted", e);
        }
    }

    /**
     * Only call after account/group/job fence locks have been acquired in this same transaction.
     */
    public Created insertReserved(CommandPrincipal p, Creation c, long linkId) {
        return insertReservedMany(p, List.of(c), List.of(new IdRange(linkId, linkId + 1))).get(0);
    }

    /**
     * Batch SQL shares the caller's account/quota/job fence transaction. IDs are ranges, not
     * per-row generator calls.
     */
    public List<Created> insertReservedMany(
            CommandPrincipal p, List<Creation> creations, List<IdRange> ranges) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("Business transaction required");
        if (creations == null || creations.isEmpty() || creations.size() > 500 || ranges == null)
            throw new IllegalArgumentException("Chunk size must be 1..500");
        long total = 0, previousEnd = 0;
        for (IdRange range : ranges) {
            if (range.startInclusive() < previousEnd)
                throw new IllegalArgumentException("ID ranges must be sorted and disjoint");
            total = Math.addExact(total, range.size());
            previousEnd = range.endExclusive();
        }
        if (total != creations.size())
            throw new IllegalArgumentException("ID count differs from row count");
        for (Creation c : creations) validateCreation(c);
        for (String gid : creations.stream().map(Creation::gid).distinct().sorted().toList())
            groups.lockActive(p, gid);
        List<Object[]> routeRows = new ArrayList<>(),
                linkRows = new ArrayList<>(),
                policyRows = new ArrayList<>();
        List<BusinessOutbox.Entry> events = new ArrayList<>();
        List<Created> result = new ArrayList<>();
        Map<String, Long> groupCounts = new TreeMap<>();
        int index = 0;
        long now = clock.millis();
        for (IdRange range : ranges)
            for (long linkId = range.startInclusive(); linkId < range.endExclusive(); linkId++) {
                Creation c = creations.get(index++);
                String domain = new HostNormalizer().normalize(c.domain(), "https"),
                        code = codec.encode(linkId),
                        full = domain + "/" + code;
                Timestamp expiry = c.validDateType() == 0 ? null : new Timestamp(c.expireAt());
                routeRows.add(
                        new Object[] {
                            linkId,
                            p.tenantId(),
                            c.gid(),
                            domain,
                            code,
                            c.originUrl(),
                            expiry,
                            now,
                            now
                        });
                linkRows.add(
                        new Object[] {
                            linkId,
                            p.tenantId(),
                            c.gid(),
                            domain,
                            code,
                            full,
                            c.originUrl(),
                            c.createdType(),
                            c.validDateType(),
                            expiry,
                            c.describe()
                        });
                policyRows.add(new Object[] {p.tenantId(), linkId});
                groupCounts.merge(c.gid(), 1L, Long::sum);
                String event = UUID.randomUUID().toString();
                events.add(
                        new BusinessOutbox.Entry(
                                event,
                                Topics.ROUTE_CHANGE,
                                p.tenantId() + ":" + linkId,
                                new RouteChangeV1(
                                        event,
                                        1,
                                        now,
                                        Long.toString(p.tenantId()),
                                        linkId,
                                        1,
                                        domain,
                                        code)));
                event = UUID.randomUUID().toString();
                events.add(
                        new BusinessOutbox.Entry(
                                event,
                                "shortlink.metadata.fetch.v1",
                                p.tenantId() + ":" + linkId,
                                metadataEvent(event, p.tenantId(), linkId, 1, c.originUrl())));
                result.add(
                        new Created(linkId, "https://" + full, c.originUrl(), c.gid(), code, 1, 1));
            }
        jdbc.batchUpdate(
                "INSERT INTO"
                    + " t_link_route(link_id,tenant_id,current_gid,domain_norm,short_uri,origin_url,expire_at,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                routeRows,
                new int[] {
                    Types.BIGINT,
                    Types.BIGINT,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.TIMESTAMP,
                    Types.BIGINT,
                    Types.BIGINT
                });
        jdbc.batchUpdate(
                "INSERT INTO"
                    + " t_link(id,tenant_id,gid,domain,short_uri,full_short_url,origin_url,created_type,valid_date_type,valid_date,describe_text)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                linkRows,
                new int[] {
                    Types.BIGINT,
                    Types.BIGINT,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.INTEGER,
                    Types.INTEGER,
                    Types.TIMESTAMP,
                    Types.VARCHAR
                });
        jdbc.batchUpdate(
                "INSERT INTO t_policy_resource(tenant_id,link_id) VALUES (?,?)", policyRows);
        groupCounts.forEach((gid, count) -> groups.adjustReferences(p, gid, count, 0));
        outbox.appendMany(events);
        return List.copyOf(result);
    }

    public Route get(CommandPrincipal p, long id) {
        auth.check(p, false);
        return requireRoute(p, id, false);
    }

    public Route byAddress(CommandPrincipal p, String full) {
        URI uri = URI.create(full.contains("://") ? full : "https://" + full);
        String domain = new HostNormalizer().normalize(uri.getRawAuthority(), uri.getScheme());
        var rows =
                jdbc.query(
                        "SELECT * FROM t_link_route WHERE domain_norm=? AND short_uri=? AND"
                                + " tenant_id=?",
                        this::row,
                        domain,
                        uri.getPath().substring(1),
                        p.tenantId());
        if (rows.size() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Link unavailable");
        return rows.get(0);
    }

    public Route requireRoute(CommandPrincipal p, long id, boolean locked) {
        var rows =
                jdbc.query(
                        "SELECT * FROM t_link_route WHERE link_id=? AND tenant_id=?"
                                + (locked ? " FOR UPDATE" : ""),
                        this::row,
                        id,
                        p.tenantId());
        if (rows.size() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Link unavailable");
        return rows.get(0);
    }

    public void update(
            CommandPrincipal p,
            long id,
            long expectedVersion,
            String newGid,
            String originUrl,
            Long expireAt,
            String description) {
        tx.executeWithoutResult(
                s -> {
                    auth.check(p, true);
                    Route before = requireRoute(p, id, false);
                    String targetGroup = newGid == null ? before.gid() : newGid;
                    for (String gid : new TreeSet<>(List.of(before.gid(), targetGroup)))
                        groups.lockActive(p, gid);
                    Route old = requireRoute(p, id, true);
                    if (old.version() != expectedVersion) conflict("Route changed");
                    if (!old.state().equals("ACTIVE")) conflict("Link is not active");
                    String target = originUrl == null ? old.originUrl() : originUrl;
                    validateCreation(
                            new Creation(
                                    old.domain(),
                                    target,
                                    targetGroup,
                                    0,
                                    expireAt == null ? 0 : 1,
                                    expireAt,
                                    description));
                    boolean moved = !old.gid().equals(targetGroup),
                            changed = !old.originUrl().equals(target);
                    long targetRevision = old.targetRevision() + (changed ? 1 : 0);
                    if (moved) {
                        moveDetail(p, id, old.gid(), targetGroup);
                        groups.adjustReferences(p, old.gid(), -1, 0);
                        groups.adjustReferences(p, targetGroup, 1, 0);
                    }
                    jdbc.update(
                            "UPDATE t_link SET"
                                + " origin_url=?,valid_date_type=?,valid_date=?,describe_text=?,target_revision=?,metadata_status=CASE"
                                + " WHEN ? THEN 'PENDING' ELSE metadata_status END,favicon=CASE"
                                + " WHEN ? THEN NULL ELSE favicon END,title=CASE WHEN ? THEN NULL"
                                + " ELSE title END,update_time=CURRENT_TIMESTAMP WHERE gid=? AND"
                                + " id=? AND tenant_id=?",
                            target,
                            expireAt == null ? 0 : 1,
                            expireAt == null ? null : new Timestamp(expireAt),
                            description,
                            targetRevision,
                            changed,
                            changed,
                            changed,
                            targetGroup,
                            id,
                            p.tenantId());
                    jdbc.update(
                            "UPDATE t_link_route SET"
                                + " current_gid=?,origin_url=?,expire_at=?,route_version=route_version+1,ownership_version=ownership_version+?,target_revision=?,metadata_status=CASE"
                                + " WHEN ? THEN 'PENDING' ELSE metadata_status END,updated_at=?"
                                + " WHERE link_id=? AND tenant_id=?",
                            targetGroup,
                            target,
                            expireAt == null ? null : new Timestamp(expireAt),
                            moved ? 1 : 0,
                            targetRevision,
                            changed,
                            clock.millis(),
                            id,
                            p.tenantId());
                    routeChanged(p.tenantId(), id, old.version() + 1, old.domain(), old.shortUri());
                    if (changed) metadataIntent(p.tenantId(), id, targetRevision, target);
                });
    }

    public void transition(CommandPrincipal p, long id, long expectedVersion, String state) {
        if (!Set.of("ACTIVE", "DISABLED", "DELETED").contains(state))
            throw new IllegalArgumentException("Invalid lifecycle state");
        tx.executeWithoutResult(
                s -> {
                    auth.check(p, true);
                    if (state.equals("DELETED")) quota.lock(p.tenantId());
                    Route before = requireRoute(p, id, false);
                    groups.lockActive(p, before.gid());
                    Route old = requireRoute(p, id, true);
                    if (old.version() != expectedVersion) conflict("Route changed");
                    if (old.state().equals("DELETED"))
                        conflict("Permanent tombstone cannot be reused");
                    if (state.equals(old.state())) return;
                    if (state.equals("DELETED") && !old.state().equals("DISABLED"))
                        conflict("Recycle before permanent delete");
                    jdbc.update(
                            "UPDATE t_link_route SET"
                                + " route_status=?,route_version=route_version+1,ownership_version=ownership_version+1,updated_at=?"
                                + " WHERE link_id=? AND tenant_id=?",
                            state,
                            clock.millis(),
                            id,
                            p.tenantId());
                    if (state.equals("DELETED")) {
                        jdbc.update(
                                "DELETE FROM t_link WHERE gid=? AND id=? AND tenant_id=?",
                                old.gid(),
                                id,
                                p.tenantId());
                        groups.adjustReferences(p, old.gid(), -1, 0);
                        quota.releaseDeleted(p.tenantId());
                    } else
                        jdbc.update(
                                "UPDATE t_link SET enable_status=?,update_time=CURRENT_TIMESTAMP"
                                        + " WHERE gid=? AND id=? AND tenant_id=?",
                                state.equals("ACTIVE") ? 0 : 1,
                                old.gid(),
                                id,
                                p.tenantId());
                    routeChanged(p.tenantId(), id, old.version() + 1, old.domain(), old.shortUri());
                });
    }

    public List<Route> page(
            CommandPrincipal p, String gid, long after, int limit, boolean recycled) {
        auth.check(p, false);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid page size");
        var groupsOwned = groups.list(p);
        if (groupsOwned.stream().noneMatch(g -> g.gid().equals(gid))) CommandAuthorization.denied();
        return jdbc.query(
                "SELECT * FROM t_link_route WHERE tenant_id=? AND current_gid=? AND link_id>? AND"
                        + " route_status=? ORDER BY link_id LIMIT ?",
                this::row,
                p.tenantId(),
                gid,
                after,
                recycled ? "DISABLED" : "ACTIVE",
                limit);
    }

    private void moveDetail(CommandPrincipal p, long id, String oldGid, String newGid) {
        String[] columns = {
            "id",
            "tenant_id",
            "gid",
            "domain",
            "short_uri",
            "full_short_url",
            "origin_url",
            "created_type",
            "valid_date_type",
            "valid_date",
            "enable_status",
            "describe_text",
            "favicon",
            "title",
            "target_revision",
            "metadata_status",
            "del_flag",
            "del_time",
            "create_time",
            "update_time"
        };
        Map<String, Object> detail =
                jdbc.queryForMap(
                        "SELECT "
                                + String.join(",", columns)
                                + " FROM t_link WHERE gid=? AND id=? AND tenant_id=?",
                        oldGid,
                        id,
                        p.tenantId());
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++)
            values[i] = columns[i].equals("gid") ? newGid : detail.get(columns[i]);
        // Separate routed statements are supported across logical shard keys in one ds_0
        // transaction.
        if (jdbc.update(
                        "DELETE FROM t_link WHERE gid=? AND id=? AND tenant_id=?",
                        oldGid,
                        id,
                        p.tenantId())
                != 1) throw new IllegalStateException("Moved detail disappeared");
        // Different gids may map to the same physical table. Delete then insert under the
        // same transaction avoids a duplicate primary key; rollback restores the source.
        jdbc.update(
                "INSERT INTO t_link("
                        + String.join(",", columns)
                        + ") VALUES ("
                        + String.join(",", Collections.nCopies(columns.length, "?"))
                        + ")",
                values);
    }

    private void routeChanged(long tenant, long id, long version, String domain, String code) {
        String event = UUID.randomUUID().toString();
        outbox.append(
                event,
                Topics.ROUTE_CHANGE,
                tenant + ":" + id,
                new RouteChangeV1(
                        event,
                        1,
                        clock.millis(),
                        Long.toString(tenant),
                        id,
                        version,
                        domain,
                        code));
    }

    private Map<String, Object> metadataEvent(
            String event, long tenant, long id, long revision, String url) {
        return Map.of(
                "eventId",
                event,
                "schemaVersion",
                1,
                "tenantId",
                Long.toString(tenant),
                "linkId",
                id,
                "targetRevision",
                revision,
                "urlDigest",
                digest(url),
                "originUrl",
                url);
    }

    private void metadataIntent(long tenant, long id, long revision, String url) {
        String event = UUID.randomUUID().toString();
        outbox.append(
                event,
                "shortlink.metadata.fetch.v1",
                tenant + ":" + id,
                metadataEvent(event, tenant, id, revision, url));
    }

    public void validateCreation(Creation c) {
        if (c == null || c.gid() == null || c.gid().isBlank() || c.gid().length() > 64)
            throw new IllegalArgumentException("Invalid group");
        String domain = new HostNormalizer().normalize(c.domain(), "https");
        if (!allowedDomains.contains(domain))
            throw new IllegalArgumentException("Unconfigured short-link domain");
        if (c.originUrl() == null
                || c.originUrl().length() > 2048
                || c.originUrl().chars().anyMatch(ch -> ch < 32))
            throw new IllegalArgumentException("Invalid target");
        URI uri = URI.create(c.originUrl());
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Target must be an absolute HTTP(S) URL");
        if (c.createdType() != 0 && c.createdType() != 1)
            throw new IllegalArgumentException("Invalid creation type");
        if (c.validDateType() != 0 && c.validDateType() != 1)
            throw new IllegalArgumentException("Invalid validity type");
        if (c.validDateType() == 1 && (c.expireAt() == null || c.expireAt() <= clock.millis()))
            throw new IllegalArgumentException("Expiry must be in the future");
        if (c.describe() != null && c.describe().length() > 1024)
            throw new IllegalArgumentException("Description too long");
    }

    private String serialize(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid payload", e);
        }
    }

    public String digest(Object o) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(serialize(o).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 96)
            throw new IllegalArgumentException("requestId must be 1..96 characters");
    }

    private static void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
