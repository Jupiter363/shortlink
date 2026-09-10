package com.jupiter.shortlink.command.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.command.security.*;
import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.risk.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.*;

@Service
public class PolicyCommandService {
    public record Payload(
            boolean disabled,
            boolean timeUnrestricted,
            String timezone,
            List<AllowedTimeWindow> allowedWindows,
            Set<String> blockedIpHashes,
            RateLimitRule rateLimit) {}

    public record Evidence(
            String snapshotId,
            String recoveryEpoch,
            long effectiveEnd,
            long evidenceCreatedAt,
            long executeBefore) {}

    public record Mutation(
            String commandId,
            long linkId,
            String policyId,
            String action,
            Payload payload,
            long effectiveFrom,
            long effectiveUntil,
            long expectedPolicyRevision,
            Evidence evidence,
            boolean automatic) {}

    public record Revocation(String commandId, long linkId, String policyId) {}

    public record CommandResult(
            String commandId,
            String status,
            String policyId,
            long policyRevision,
            long committedAt) {}

    public record PolicyFact(
            String policyId,
            String action,
            long effectiveFrom,
            long effectiveUntil,
            boolean revoked,
            boolean active) {}

    public record PolicyPage(
            long policyRevision,
            long asOf,
            Long nextTransitionAt,
            PolicyState state,
            List<PolicyFact> policies,
            String nextCursor) {}

    private record Policy(String id, String action, Payload payload, long from, long until) {}

    private record Resource(long revision, Long next) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommandAuthorization auth;
    private final BusinessOutbox outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public PolicyCommandService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            CommandAuthorization auth,
            BusinessOutbox outbox,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.auth = auth;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    public CommandResult activate(CommandPrincipal p, Mutation q) {
        validateIdentity(q.commandId(), q.policyId(), q.linkId());
        return tx.execute(
                s -> {
                    auth.check(p, true);
                    String digest = serialized(q);
                    CommandResult previous = previous(p.tenantId(), q.commandId(), digest);
                    if (previous != null) return previous;
                    requireResource(p.tenantId(), q.linkId());
                    Resource resource = lock(p.tenantId(), q.linkId());
                    long now = clock.millis();
                    if (resource.revision() != q.expectedPolicyRevision()
                            || (resource.next() != null && resource.next() <= now))
                        return persist(
                                p.tenantId(),
                                q.commandId(),
                                digest,
                                new CommandResult(
                                        q.commandId(),
                                        "CONFLICT",
                                        q.policyId(),
                                        resource.revision(),
                                        now));
                    if (q.automatic() && !validEvidence(q.evidence(), now))
                        return persist(
                                p.tenantId(),
                                q.commandId(),
                                digest,
                                new CommandResult(
                                        q.commandId(),
                                        "EXPIRED",
                                        q.policyId(),
                                        resource.revision(),
                                        now));
                    validatePayload(q, now);
                    List<Policy> policies = policies(p.tenantId(), q.linkId());
                    Long priorPolicy =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM t_risk_policy WHERE tenant_id=? AND"
                                            + " link_id=? AND policy_id=?",
                                    Long.class,
                                    p.tenantId(),
                                    q.linkId(),
                                    q.policyId());
                    if (priorPolicy != null && priorPolicy != 0)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Policy ID already exists");
                    if (policies.size() >= 64)
                        throw new IllegalArgumentException("Resource policy budget exceeded");
                    if (q.action().equals("BLOCK_IP")) {
                        Set<String> all = new HashSet<>(q.payload().blockedIpHashes());
                        for (Policy policy : policies)
                            if (policy.action().equals("BLOCK_IP"))
                                all.addAll(policy.payload().blockedIpHashes());
                        if (all.size() > 4096)
                            throw new IllegalArgumentException(
                                    "Resource IP policy budget exceeded");
                    }
                    if (q.action().equals("LIMIT_RATE")) {
                        int window = q.payload().rateLimit().windowSeconds();
                        for (Policy policy : policies) {
                            if (policy.action().equals("LIMIT_RATE")
                                    && policy.until() > now
                                    && policy.payload().rateLimit().windowSeconds() != window)
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Active rate window is immutable");
                        }
                        var held =
                                jdbc.queryForList(
                                                "SELECT rate_window_seconds,rate_window_lock_until"
                                                    + " FROM t_policy_resource WHERE tenant_id=?"
                                                    + " AND link_id=? FOR UPDATE",
                                                p.tenantId(),
                                                q.linkId())
                                        .get(0);
                        if (held.get("rate_window_seconds") != null
                                && ((Number) held.get("rate_window_seconds")).intValue() != window
                                && held.get("rate_window_lock_until") != null
                                && ((Number) held.get("rate_window_lock_until")).longValue() > now)
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Current rate budget window is still active");
                        // A future rule has consumed no current-window quota. Existing holds
                        // from earlier effective rules must survive this insertion.
                        long boundary = q.effectiveFrom() <= now ? windowEnd(now, window) : 0;
                        jdbc.update(
                                "UPDATE t_policy_resource SET"
                                        + " rate_window_seconds=?,rate_window_lock_until="
                                        + "GREATEST(COALESCE(rate_window_lock_until,0),?) WHERE"
                                        + " tenant_id=? AND link_id=?",
                                window,
                                boundary,
                                p.tenantId(),
                                q.linkId());
                    }
                    if (q.automatic())
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .registerSynchronization(
                                        new org.springframework.transaction.support
                                                .TransactionSynchronization() {
                                            @Override
                                            public void beforeCommit(boolean readOnly) {
                                                if (!validEvidence(q.evidence(), clock.millis()))
                                                    throw new ResponseStatusException(
                                                            HttpStatus.GONE,
                                                            "Evidence expired before commit");
                                            }
                                        });
                    jdbc.update(
                            "INSERT INTO"
                                + " t_risk_policy(tenant_id,link_id,policy_id,action,payload,effective_from,effective_until,created_at)"
                                + " VALUES (?,?,?,?,?,?,?,?)",
                            p.tenantId(),
                            q.linkId(),
                            q.policyId(),
                            q.action(),
                            serialized(q.payload()),
                            q.effectiveFrom(),
                            q.effectiveUntil(),
                            now);
                    long revision = resource.revision() + 1;
                    updateResource(
                            p.tenantId(),
                            q.linkId(),
                            revision,
                            nextTransition(policies(p.tenantId(), q.linkId()), now));
                    changed(p.tenantId(), q.linkId(), revision);
                    return persist(
                            p.tenantId(),
                            q.commandId(),
                            digest,
                            new CommandResult(
                                    q.commandId(), "COMMITTED", q.policyId(), revision, now));
                });
    }

    public CommandResult revoke(CommandPrincipal p, Revocation q) {
        validateIdentity(q.commandId(), q.policyId(), q.linkId());
        return tx.execute(
                s -> {
                    auth.check(p, true);
                    String digest = serialized(q);
                    CommandResult prior = previous(p.tenantId(), q.commandId(), digest);
                    if (prior != null) return prior;
                    requireResource(p.tenantId(), q.linkId());
                    Resource resource = reconcileLocked(p.tenantId(), q.linkId());
                    long now = clock.millis();
                    var policy =
                            jdbc.queryForList(
                                    "SELECT action,payload,effective_from,effective_until,revoked"
                                            + " FROM t_risk_policy WHERE tenant_id=? AND"
                                            + " link_id=? AND policy_id=? FOR UPDATE",
                                    p.tenantId(),
                                    q.linkId(),
                                    q.policyId());
                    if (policy.isEmpty())
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Policy unavailable");
                    var fact = policy.get(0);
                    boolean revoked =
                            Boolean.TRUE.equals(fact.get("revoked"))
                                    || (fact.get("revoked") instanceof Number n
                                            && n.intValue() != 0);
                    if (!revoked
                            && "LIMIT_RATE".equals(fact.get("action"))
                            && ((Number) fact.get("effective_from")).longValue() <= now
                            && now < ((Number) fact.get("effective_until")).longValue()) {
                        retainRateWindow(
                                p.tenantId(),
                                q.linkId(),
                                parsed(fact.get("payload").toString(), Payload.class).rateLimit(),
                                now,
                                now);
                    }
                    jdbc.update(
                            "UPDATE t_risk_policy SET revoked=TRUE WHERE tenant_id=? AND link_id=?"
                                    + " AND policy_id=?",
                            p.tenantId(),
                            q.linkId(),
                            q.policyId());
                    long revision = resource.revision() + 1;
                    updateResource(
                            p.tenantId(),
                            q.linkId(),
                            revision,
                            nextTransition(policies(p.tenantId(), q.linkId()), now));
                    changed(p.tenantId(), q.linkId(), revision);
                    return persist(
                            p.tenantId(),
                            q.commandId(),
                            digest,
                            new CommandResult(
                                    q.commandId(), "COMMITTED", q.policyId(), revision, now));
                });
    }

    public CommandResult result(CommandPrincipal p, String commandId) {
        auth.check(p, false);
        var rows =
                jdbc.queryForList(
                        "SELECT result_json FROM t_command_result WHERE tenant_id=? AND"
                                + " command_id=?",
                        String.class,
                        p.tenantId(),
                        "risk:" + commandId);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Command result unavailable");
        return parsed(rows.get(0), CommandResult.class);
    }

    private CommandResult previous(long tenant, String id, String digest) {
        var rows =
                jdbc.queryForList(
                        "SELECT request_digest,result_json FROM t_command_result WHERE tenant_id=?"
                                + " AND command_id=?",
                        tenant,
                        "risk:" + id);
        if (rows.isEmpty()) return null;
        if (!hash(digest).equals(rows.get(0).get("request_digest")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Command ID payload differs");
        return parsed((String) rows.get(0).get("result_json"), CommandResult.class);
    }

    private CommandResult persist(long tenant, String id, String digest, CommandResult result) {
        jdbc.update(
                "INSERT INTO"
                    + " t_command_result(tenant_id,command_id,request_digest,result_json,created_at)"
                    + " VALUES (?,?,?,?,?)",
                tenant,
                "risk:" + id,
                hash(digest),
                serialized(result),
                clock.millis());
        return result;
    }

    private void requireResource(long tenant, long id) {
        var rows =
                jdbc.queryForList(
                        "SELECT link_id FROM t_link_route WHERE tenant_id=? AND link_id=? AND"
                                + " route_status<>'DELETED' FOR SHARE",
                        Long.class,
                        tenant,
                        id);
        if (rows.size() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource unavailable");
    }

    private Resource lock(long tenant, long id) {
        return jdbc.query(
                "SELECT policy_revision,next_transition_at FROM t_policy_resource WHERE tenant_id=?"
                        + " AND link_id=? FOR UPDATE",
                r -> {
                    if (!r.next())
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Policy resource unavailable");
                    long revision = r.getLong(1);
                    long next = r.getLong(2);
                    return new Resource(revision, r.wasNull() ? null : next);
                },
                tenant,
                id);
    }

    private Resource reconcileLocked(long tenant, long id) {
        Resource r = lock(tenant, id);
        long now = clock.millis();
        if (r.next() != null && r.next() <= now) {
            // The previous transition bounds this to the resource's last live/future set
            // (at most 64 policies). Activation cannot bypass an overdue transition, so
            // this range also includes expirations missed during a process outage.
            List<Policy> previous = policiesAfter(tenant, id, r.next() - 1);
            if (previous.size() > 64)
                throw new IllegalStateException("Resource policy budget exceeded");
            for (Policy policy : previous) {
                if (policy.action().equals("LIMIT_RATE") && policy.until() <= now) {
                    retainRateWindow(
                            tenant, id, policy.payload().rateLimit(), policy.until() - 1, now);
                }
            }
            long revision = r.revision() + 1;
            Long next =
                    nextTransition(previous.stream().filter(p -> p.until() > now).toList(), now);
            updateResource(tenant, id, revision, next);
            changed(tenant, id, revision);
            return new Resource(revision, next);
        }
        return r;
    }

    private List<Policy> policies(long tenant, long id) {
        return policiesAfter(tenant, id, clock.millis());
    }

    private List<Policy> policiesAfter(long tenant, long id, long cutoff) {
        return jdbc.query(
                "SELECT policy_id,action,payload,effective_from,effective_until FROM t_risk_policy"
                        + " WHERE tenant_id=? AND link_id=? AND revoked=FALSE AND effective_until>?"
                        + " ORDER BY policy_id LIMIT 65 FOR UPDATE",
                (r, n) ->
                        new Policy(
                                r.getString(1),
                                r.getString(2),
                                parsed(r.getString(3), Payload.class),
                                r.getLong(4),
                                r.getLong(5)),
                tenant,
                id,
                cutoff);
    }

    /** Caller already owns the resource lock. Keeping the old window does not add a lock order. */
    private void retainRateWindow(
            long tenant, long id, RateLimitRule rule, long affectedAt, long now) {
        long boundary = windowEnd(affectedAt, rule.windowSeconds());
        if (boundary <= now) return;
        jdbc.update(
                "UPDATE t_policy_resource SET"
                    + " rate_window_seconds=?,rate_window_lock_until=GREATEST(COALESCE(rate_window_lock_until,0),?)"
                    + " WHERE tenant_id=? AND link_id=?",
                rule.windowSeconds(),
                boundary,
                tenant,
                id);
    }

    private static long windowEnd(long timestamp, int seconds) {
        long size = seconds * 1000L;
        return Math.multiplyExact(Math.floorDiv(timestamp, size) + 1, size);
    }

    private Long nextTransition(List<Policy> policies, long now) {
        return policies.stream()
                .flatMap(policy -> java.util.stream.Stream.of(policy.from(), policy.until()))
                .filter(t -> t > now)
                .min(Long::compareTo)
                .orElse(null);
    }

    private void updateResource(long tenant, long id, long revision, Long next) {
        jdbc.update(
                "UPDATE t_policy_resource SET policy_revision=?,next_transition_at=? WHERE"
                        + " tenant_id=? AND link_id=?",
                revision,
                next,
                tenant,
                id);
    }

    private void changed(long tenant, long id, long revision) {
        String event = UUID.randomUUID().toString();
        Long next =
                jdbc.queryForObject(
                        "SELECT next_transition_at FROM t_policy_resource WHERE tenant_id=? AND"
                                + " link_id=?",
                        Long.class,
                        tenant,
                        id);
        outbox.append(
                event,
                Topics.RISK_POLICY_CHANGE,
                tenant + ":" + id,
                new RiskPolicyChangeV1(
                        event,
                        1,
                        clock.millis(),
                        Long.toString(tenant),
                        tenant + ":" + id,
                        "AGGREGATE",
                        revision,
                        next));
    }

    public PolicySnapshot snapshot(long tenant, long id) {
        return tx.execute(
                s -> {
                    requireResource(tenant, id);
                    Resource resource = reconcileLocked(tenant, id);
                    long now = clock.millis();
                    boolean disabled = false, unrestricted = true;
                    List<AllowedTimeWindow> windows = List.of(new AllowedTimeWindow(0, 86400));
                    Set<String> blocked = new HashSet<>();
                    RateLimitRule rate = null;
                    for (Policy p : policies(tenant, id)) {
                        if (p.from() > now || now >= p.until()) continue;
                        switch (p.action()) {
                            case "DISABLE_SHORT_LINK" -> disabled = true;
                            case "ALLOW_TIME_WINDOW" -> {
                                if (!p.payload().timeUnrestricted()) {
                                    unrestricted = false;
                                    windows = intersection(windows, p.payload().allowedWindows());
                                }
                            }
                            case "BLOCK_IP" -> blocked.addAll(p.payload().blockedIpHashes());
                            case "LIMIT_RATE" -> {
                                RateLimitRule next = p.payload().rateLimit();
                                if (rate != null && rate.windowSeconds() != next.windowSeconds())
                                    throw new IllegalStateException(
                                            "Incompatible committed rate windows");
                                if (rate == null || next.limit() < rate.limit()) rate = next;
                            }
                            default -> throw new IllegalStateException("Unknown committed policy");
                        }
                    }
                    PolicyState state =
                            disabled || !unrestricted || !blocked.isEmpty() || rate != null
                                    ? PolicyState.KNOWN_RESTRICTED
                                    : PolicyState.KNOWN_ALLOWED;
                    long until =
                            Math.min(
                                    now + 1000,
                                    resource.next() == null ? Long.MAX_VALUE : resource.next());
                    return new PolicySnapshot(
                            tenant + ":" + id,
                            resource.revision(),
                            now,
                            resource.next(),
                            until,
                            state,
                            disabled,
                            unrestricted,
                            "Asia/Shanghai",
                            unrestricted ? List.of() : windows,
                            blocked,
                            rate);
                });
    }

    static List<AllowedTimeWindow> intersection(
            List<AllowedTimeWindow> left, List<AllowedTimeWindow> right) {
        List<AllowedTimeWindow> a = normalized(left),
                b = normalized(right),
                result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            int start = Math.max(a.get(i).startSecond(), b.get(j).startSecond()),
                    end = Math.min(a.get(i).endSecond(), b.get(j).endSecond());
            if (start < end) result.add(new AllowedTimeWindow(start, end));
            if (a.get(i).endSecond() < b.get(j).endSecond()) i++;
            else j++;
        }
        return List.copyOf(result);
    }

    private static List<AllowedTimeWindow> normalized(List<AllowedTimeWindow> windows) {
        List<AllowedTimeWindow> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparingInt(AllowedTimeWindow::startSecond));
        List<AllowedTimeWindow> out = new ArrayList<>();
        for (var w : sorted) {
            if (out.isEmpty() || out.get(out.size() - 1).endSecond() < w.startSecond()) out.add(w);
            else {
                var prior = out.remove(out.size() - 1);
                out.add(
                        new AllowedTimeWindow(
                                prior.startSecond(), Math.max(prior.endSecond(), w.endSecond())));
            }
        }
        return out;
    }

    public PolicyPage policyPage(CommandPrincipal p, long linkId, String after) {
        return tx.execute(
                status -> {
                    auth.check(p, false);
                    PolicySnapshot snapshot = snapshot(p.tenantId(), linkId);
                    if (after != null && after.length() > 128)
                        throw new IllegalArgumentException("Invalid policy cursor");
                    List<PolicyFact> facts =
                            jdbc.query(
                                    "SELECT policy_id,action,effective_from,effective_until,revoked"
                                        + " FROM t_risk_policy WHERE tenant_id=? AND link_id=? AND"
                                        + " policy_id>? ORDER BY policy_id LIMIT 101 FOR SHARE",
                                    (r, n) ->
                                            new PolicyFact(
                                                    r.getString(1),
                                                    r.getString(2),
                                                    r.getLong(3),
                                                    r.getLong(4),
                                                    r.getBoolean(5),
                                                    !r.getBoolean(5)
                                                            && r.getLong(3)
                                                                    <= snapshot.evaluatedAt()
                                                            && snapshot.evaluatedAt()
                                                                    < r.getLong(4)),
                                    p.tenantId(),
                                    linkId,
                                    after == null ? "" : after);
                    boolean more = facts.size() > 100;
                    List<PolicyFact> page =
                            more ? List.copyOf(facts.subList(0, 100)) : List.copyOf(facts);
                    return new PolicyPage(
                            snapshot.policyRevision(),
                            snapshot.evaluatedAt(),
                            snapshot.nextTransitionAt(),
                            snapshot.state(),
                            page,
                            more ? page.get(page.size() - 1).policyId() : null);
                });
    }

    public boolean ready() {
        return Integer.valueOf(1)
                .equals(
                        jdbc.queryForObject(
                                "SELECT singleton_id FROM t_analytics_epoch WHERE singleton_id=1",
                                Integer.class));
    }

    private boolean validEvidence(Evidence e, long now) {
        if (e == null
                || e.snapshotId() == null
                || e.snapshotId().isBlank()
                || e.recoveryEpoch() == null
                || e.effectiveEnd() > now
                || e.evidenceCreatedAt() > now
                || e.effectiveEnd() < 0
                || e.evidenceCreatedAt() < 0) return false;
        long deadline;
        try {
            deadline =
                    Math.min(
                            Math.addExact(e.effectiveEnd(), 120000),
                            Math.addExact(e.evidenceCreatedAt(), 600000));
        } catch (ArithmeticException ex) {
            return false;
        }
        if (now >= e.executeBefore() || e.executeBefore() > deadline) return false;
        var epochs =
                jdbc.queryForList(
                        "SELECT recovery_epoch,actions_enabled FROM t_analytics_epoch WHERE"
                                + " singleton_id=1 FOR UPDATE");
        if (epochs.size() != 1) return false;
        Object enabled = epochs.get(0).get("actions_enabled");
        return (Boolean.TRUE.equals(enabled) || (enabled instanceof Number n && n.intValue() == 1))
                && e.recoveryEpoch().equals(epochs.get(0).get("recovery_epoch"));
    }

    private void validatePayload(Mutation q, long now) {
        if (q.effectiveFrom() < 0
                || q.effectiveUntil() <= Math.max(now, q.effectiveFrom())
                || q.effectiveUntil() - q.effectiveFrom() > 86400000L * 30)
            throw new IllegalArgumentException("Invalid policy lifetime");
        if (q.payload() == null || q.action() == null)
            throw new IllegalArgumentException("Policy action and payload required");
        if (q.payload().timezone() != null && !q.payload().timezone().equals("Asia/Shanghai"))
            throw new IllegalArgumentException("Unsupported policy timezone");
        switch (q.action()) {
            case "DISABLE_SHORT_LINK" -> {}
            case "ALLOW_TIME_WINDOW" -> {
                if (q.payload().allowedWindows() == null
                        || q.payload().allowedWindows().size() > 64
                        || q.payload().allowedWindows().stream().anyMatch(Objects::isNull))
                    throw new IllegalArgumentException("Invalid windows");
            }
            case "BLOCK_IP" -> {
                if (q.payload().blockedIpHashes() == null
                        || q.payload().blockedIpHashes().isEmpty()
                        || q.payload().blockedIpHashes().size() > 1024
                        || q.payload().blockedIpHashes().stream()
                                .anyMatch(h -> h == null || !h.matches("[0-9a-f]{32}")))
                    throw new IllegalArgumentException("Invalid blocked IP hashes");
            }
            case "LIMIT_RATE" -> {
                if (q.payload().rateLimit() == null)
                    throw new IllegalArgumentException("Rate limit required");
            }
            default -> throw new IllegalArgumentException("Unknown policy action");
        }
    }

    @Scheduled(fixedDelayString = "${shortlink.policy.reconcile-ms:200}")
    public void reconcileDue() {
        long now = clock.millis();
        var rows =
                jdbc.queryForList(
                        "SELECT tenant_id,link_id FROM t_policy_resource WHERE"
                                + " next_transition_at<=? ORDER BY next_transition_at LIMIT 100",
                        now);
        for (var row : rows)
            tx.executeWithoutResult(
                    s ->
                            reconcileLocked(
                                    ((Number) row.get("tenant_id")).longValue(),
                                    ((Number) row.get("link_id")).longValue()));
    }

    public long pause() {
        return tx.execute(
                s -> {
                    jdbc.queryForObject(
                            "SELECT gate_fence FROM t_analytics_epoch WHERE singleton_id=1 FOR"
                                    + " UPDATE",
                            Long.class);
                    jdbc.update(
                            "UPDATE t_analytics_epoch SET"
                                    + " actions_enabled=FALSE,gate_fence=gate_fence+1,updated_at=?"
                                    + " WHERE singleton_id=1",
                            clock.millis());
                    return jdbc.queryForObject(
                            "SELECT gate_fence FROM t_analytics_epoch WHERE singleton_id=1",
                            Long.class);
                });
    }

    public void activateEpoch(String epoch, long gateFence) {
        if (epoch == null || !epoch.matches("[A-Za-z0-9._:-]{16,128}"))
            throw new IllegalArgumentException("Invalid recovery epoch");
        tx.executeWithoutResult(
                s -> {
                    var existing =
                            jdbc.queryForList(
                                    "SELECT recovery_epoch,actions_enabled,gate_fence FROM"
                                            + " t_analytics_epoch WHERE singleton_id=1 FOR UPDATE");
                    if (existing.size() != 1)
                        throw new IllegalStateException("Recovery control row absent");
                    var row = existing.get(0);
                    if (((Number) row.get("gate_fence")).longValue() != gateFence)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Recovery fence superseded");
                    Object enabled = row.get("actions_enabled");
                    boolean active =
                            Boolean.TRUE.equals(enabled)
                                    || (enabled instanceof Number n && n.intValue() == 1);
                    if (active && epoch.equals(row.get("recovery_epoch"))) return;
                    if (active)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Pause before activating a new epoch");
                    Long used =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM t_analytics_epoch_history WHERE"
                                            + " recovery_epoch=?",
                                    Long.class,
                                    epoch);
                    if (used != null && used > 0)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Recovery epoch cannot be reused");
                    jdbc.update(
                            "INSERT INTO"
                                + " t_analytics_epoch_history(recovery_epoch,gate_fence,activated_at)"
                                + " VALUES (?,?,?)",
                            epoch,
                            gateFence,
                            clock.millis());
                    jdbc.update(
                            "UPDATE t_analytics_epoch SET"
                                    + " recovery_epoch=?,actions_enabled=TRUE,updated_at=? WHERE"
                                    + " singleton_id=1",
                            epoch,
                            clock.millis());
                });
    }

    private static void validateIdentity(String command, String policy, long id) {
        if (command == null
                || command.isBlank()
                || command.length() > 96
                || policy == null
                || policy.isBlank()
                || policy.length() > 128
                || id < 1) throw new IllegalArgumentException("Invalid command identity");
    }

    private String serialized(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid policy", e);
        }
    }

    private <T> T parsed(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt policy record", e);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
