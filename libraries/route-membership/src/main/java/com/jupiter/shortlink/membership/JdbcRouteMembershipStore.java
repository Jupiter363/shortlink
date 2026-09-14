package com.jupiter.shortlink.membership;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Primary-database membership authority. The singleton control lock serializes registration,
 * lease grants, publication fences and administrative transitions. No wall clock grants safety.
 */
public final class JdbcRouteMembershipStore {
    public static final String NAMESPACE = "routes";
    public static final long MAX_LEASE_MILLIS = 1_000;
    public static final long PUBLICATION_MARGIN_MILLIS = 250;
    public static final long PUBLICATION_DELAY_NANOS =
            TimeUnit.MILLISECONDS.toNanos(MAX_LEASE_MILLIS + PUBLICATION_MARGIN_MILLIS);
    public static final int MAX_REGISTRATION_BATCH = 500;
    public static final int MAX_PAGE_SIZE = 1_000;

    private static final String CONTROL_COLUMNS =
            "namespace,generation,revision,member_count,mode,baseline_ready,transition_id";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final LongSupplier nanoClock;
    private final Object issuer = new Object();
    private volatile String sharedReadClause;

    public JdbcRouteMembershipStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, System::nanoTime);
    }

    public JdbcRouteMembershipStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                   LongSupplier nanoClock) {
        // Do not change a caller's shared template settings. The same DataSource identity keeps
        // registration observers and final publication verification in their intended transactions.
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource(), "dataSource"));
        this.jdbc.setQueryTimeout(2);
        this.independent = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.independent.setTimeout(5);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public RegistrationPermit register(Collection<RouteAddress> addresses) {
        return register(addresses, ignored -> { });
    }

    public RegistrationPermit register(Collection<RouteAddress> addresses, RegistrationObserver observer) {
        Set<RouteAddress> requested = checkedBatch(addresses);
        Objects.requireNonNull(observer, "observer");
        AppliedCut committed = independent.execute(status -> {
            ControlRow control = lockedControl();
            if (control.snapshot.mode() == Mode.DRAINING)
                throw new IllegalStateException("Membership maintenance blocks registration");
            return addEntries(control.snapshot, requested, observer);
        });
        // execute() only returns after commit. Commit failures never yield a publication permit.
        return new RegistrationPermit(issuer, Objects.requireNonNull(committed), requested, nanoClock.getAsLong());
    }

    public long publicationDelayNanos(RegistrationPermit permit) {
        checkPermitOwner(permit);
        return remaining(permit.committedAtNanos);
    }

    /**
     * Must run inside the business transaction, before route insertion. The control FOR UPDATE
     * lock is deliberately not released here: it fences generation/mode changes until commit.
     */
    public void verifyForPublication(RegistrationPermit permit, Collection<RouteAddress> addresses) {
        checkPermitOwner(permit);
        Set<RouteAddress> requested = checkedBatch(addresses);
        if (!permit.addresses().containsAll(requested))
            throw new IllegalArgumentException("Publication addresses differ from the registration permit");
        if (publicationDelayNanos(permit) != 0)
            throw new IllegalStateException("Membership publication grace period has not elapsed");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Membership verification requires the business database transaction");
        ControlSnapshot current = lockedControl().snapshot;
        if (current.mode() == Mode.DRAINING || !current.generation().equals(permit.generation()))
            throw new IllegalStateException("Membership publication permit is fenced by maintenance or generation");
        if (current.revision() < permit.revision())
            throw new IllegalStateException("Membership authority revision regressed");
        Map<RouteAddress, RegistryEntry> registered = entriesFor(current.generation(), requested, true);
        if (registered.size() != requested.size() || registered.values().stream()
                .anyMatch(entry -> entry.registrationRevision() > permit.revision()))
            throw new IllegalStateException("Publication address lacks its durable registration");
    }

    public ControlSnapshot readControl() {
        return control("").snapshot;
    }

    /** Bounded authoritative paging at an immutable append-only cut, including pending addresses. */
    public RegistryPage readPage(ControlSnapshot cut, long afterOrdinal, int limit) {
        Objects.requireNonNull(cut, "cut");
        if (!NAMESPACE.equals(cut.namespace()) || afterOrdinal < 0 || afterOrdinal > cut.memberCount()
                || limit < 1 || limit > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Invalid membership page bounds");
        return independent.execute(status -> {
            ControlSnapshot current = sharedControl().snapshot;
            if (!current.generation().equals(cut.generation()) || current.revision() < cut.revision()
                    || current.memberCount() < cut.memberCount())
                throw new IllegalStateException("Membership cut no longer belongs to this authority");
            List<RegistryEntry> entries = jdbc.query(
                    "SELECT member_ordinal,registration_revision,domain_norm,short_uri FROM t_route_membership "
                            + "WHERE namespace=? AND generation=? AND member_ordinal>? AND member_ordinal<=? "
                            + "ORDER BY member_ordinal LIMIT ?",
                    (rs, row) -> new RegistryEntry(rs.getLong(1), rs.getLong(2),
                            new RouteAddress(rs.getString(3), rs.getString(4))),
                    NAMESPACE, cut.generation(), afterOrdinal, cut.memberCount(), limit);
            long next = afterOrdinal;
            for (RegistryEntry entry : entries) {
                if (entry.ordinal() != next + 1 || entry.registrationRevision() > cut.revision()
                        || entry.registrationRevision() < 1)
                    throw new IllegalStateException("Membership registry has a gap or an invalid revision");
                next = entry.ordinal();
            }
            if (entries.size() < limit && next != cut.memberCount())
                throw new IllegalStateException("Membership registry is shorter than its committed count");
            return new RegistryPage(cut, entries, next, next == cut.memberCount());
        });
    }

    public Optional<DenialLease> acquireLease(AppliedCut fullyAppliedCut) {
        Objects.requireNonNull(fullyAppliedCut, "fullyAppliedCut");
        long requestStarted = nanoClock.getAsLong();
        Boolean granted = independent.execute(status -> {
            ControlSnapshot current = sharedControl().snapshot;
            return current.mode() == Mode.ENFORCE && current.baselineReady()
                    && current.cut().equals(fullyAppliedCut);
        });
        if (!Boolean.TRUE.equals(granted)) return Optional.empty();
        DenialLease lease = new DenialLease(fullyAppliedCut, requestStarted,
                TimeUnit.MILLISECONDS.toNanos(MAX_LEASE_MILLIS), nanoClock);
        return lease.isValid() ? Optional.of(lease) : Optional.empty();
    }

    /** Explicit administration only. No service startup calls this or scans existing routes. */
    public DrainPermit beginDrain() {
        String transitionId = UUID.randomUUID().toString();
        String generation = independent.execute(status -> {
            ControlRow current = lockedControl();
            jdbc.update("UPDATE t_route_membership_control SET mode='DRAINING',transition_id=? WHERE namespace=?",
                    transitionId, NAMESPACE);
            return current.snapshot.generation();
        });
        return new DrainPermit(issuer, generation, transitionId, nanoClock.getAsLong());
    }

    public long remainingDrainNanos(DrainPermit permit) {
        checkDrainOwner(permit);
        return remaining(permit.committedAtNanos);
    }

    /** Adds one explicitly supplied legacy page after admission and existing leases are drained. */
    public AppliedCut bootstrapPage(DrainPermit permit, Collection<RouteAddress> addresses) {
        Set<RouteAddress> requested = checkedBatch(addresses);
        requireDrained(permit);
        return independent.execute(status -> {
            ControlRow current = lockedControl();
            checkDrainFence(permit, current);
            return addEntries(current.snapshot, requested, ignored -> { });
        });
    }

    /** Fails closed unless every route (regardless of status) is represented in the current registry. */
    public ControlSnapshot completeBaseline(DrainPermit permit) {
        requireDrained(permit);
        return independent.execute(status -> {
            ControlRow current = lockedControl();
            checkDrainFence(permit, current);
            verifyAllRoutesCovered(current.snapshot.generation());
            verifyRegistryCount(current.snapshot);
            jdbc.update("UPDATE t_route_membership_control SET mode='SHADOW',baseline_ready=TRUE WHERE namespace=?",
                    NAMESPACE);
            return lockedControl().snapshot;
        });
    }

    /** OFF is only available after draining; entering OFF invalidates the previous baseline proof. */
    public ControlSnapshot finishDrain(DrainPermit permit, Mode target) {
        if (target == null || target == Mode.DRAINING)
            throw new IllegalArgumentException("Invalid membership transition target");
        requireDrained(permit);
        return independent.execute(status -> {
            ControlRow current = lockedControl();
            checkDrainFence(permit, current);
            if (target == Mode.ENFORCE) {
                if (!current.snapshot.baselineReady())
                    throw new IllegalStateException("Membership baseline has not been verified");
                verifyAllRoutesCovered(current.snapshot.generation());
                verifyRegistryCount(current.snapshot);
            }
            boolean ready = target != Mode.OFF && current.snapshot.baselineReady();
            jdbc.update("UPDATE t_route_membership_control SET mode=?,baseline_ready=? WHERE namespace=?",
                    target.name(), ready, NAMESPACE);
            return lockedControl().snapshot;
        });
    }

    private AppliedCut addEntries(ControlSnapshot control, Set<RouteAddress> requested,
                                  RegistrationObserver observer) {
        Map<RouteAddress, RegistryEntry> previous = entriesFor(control.generation(), requested, false);
        List<RouteAddress> additions = requested.stream().filter(address -> !previous.containsKey(address)).toList();
        if (additions.isEmpty()) return control.cut();
        long revision = Math.addExact(control.revision(), 1);
        long count = Math.addExact(control.memberCount(), additions.size());
        List<Object[]> values = new ArrayList<>(additions.size());
        long ordinal = control.memberCount();
        for (RouteAddress address : additions) {
            values.add(new Object[]{NAMESPACE, control.generation(), ++ordinal, revision,
                    address.domainNorm(), address.shortUri()});
        }
        jdbc.batchUpdate("INSERT INTO t_route_membership "
                + "(namespace,generation,member_ordinal,registration_revision,domain_norm,short_uri) "
                + "VALUES (?,?,?,?,?,?)", values);
        jdbc.update("UPDATE t_route_membership_control SET revision=?,member_count=? WHERE namespace=?",
                revision, count, NAMESPACE);
        observer.onRegistered(new RegistrationNotice(NAMESPACE, control.generation(), revision, count));
        return new AppliedCut(control.generation(), revision, count);
    }

    private Map<RouteAddress, RegistryEntry> entriesFor(String generation, Set<RouteAddress> addresses,
                                                       boolean forUpdate) {
        StringBuilder sql = new StringBuilder("SELECT member_ordinal,registration_revision,domain_norm,short_uri "
                + "FROM t_route_membership WHERE namespace=? AND generation=? AND (domain_norm,short_uri) IN (");
        List<Object> parameters = new ArrayList<>();
        parameters.add(NAMESPACE);
        parameters.add(generation);
        for (RouteAddress address : addresses) {
            if (parameters.size() > 2) sql.append(',');
            sql.append("(?,?)");
            parameters.add(address.domainNorm());
            parameters.add(address.shortUri());
        }
        sql.append(')');
        if (forUpdate) sql.append(" FOR UPDATE");
        Map<RouteAddress, RegistryEntry> result = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            RouteAddress address = new RouteAddress(rs.getString(3), rs.getString(4));
            result.put(address, new RegistryEntry(rs.getLong(1), rs.getLong(2), address));
        }, parameters.toArray());
        return result;
    }

    private void verifyAllRoutesCovered(String generation) {
        List<Integer> missing = jdbc.query("SELECT 1 FROM t_link_route r WHERE NOT EXISTS ("
                        + "SELECT 1 FROM t_route_membership m WHERE m.namespace=? AND m.generation=? "
                        + "AND m.domain_norm=r.domain_norm AND m.short_uri=r.short_uri) LIMIT 1",
                (rs, row) -> 1, NAMESPACE, generation);
        if (!missing.isEmpty()) throw new IllegalStateException("The route baseline is incomplete");
    }

    private void verifyRegistryCount(ControlSnapshot control) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_route_membership WHERE namespace=? AND generation=?",
                Long.class, NAMESPACE, control.generation());
        if (count == null || count != control.memberCount())
            throw new IllegalStateException("Membership registry count is inconsistent");
    }

    private ControlRow lockedControl() {
        requireBoundTransaction();
        return control(" FOR UPDATE");
    }

    private ControlRow sharedControl() {
        requireBoundTransaction();
        String clause = sharedReadClause;
        if (clause == null) {
            // MySQL 8.0.22+ FOR SHARE requires SELECT only and permits concurrent readers.
            // H2 has no shared-row-lock syntax; use its stronger lock for unit protocol tests.
            // The SELECT-only privilege and concurrent-reader behavior are verified on MySQL.
            clause = jdbc.execute((ConnectionCallback<String>) connection ->
                    "H2".equals(connection.getMetaData().getDatabaseProductName()) ? " FOR UPDATE" : " FOR SHARE");
            sharedReadClause = clause;
        }
        return control(clause);
    }

    private void requireBoundTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Membership transaction manager does not bind the membership DataSource");
    }

    private ControlRow control(String lockingClause) {
        ControlRow result = jdbc.queryForObject("SELECT " + CONTROL_COLUMNS
                        + " FROM t_route_membership_control WHERE namespace=?" + lockingClause,
                (rs, row) -> new ControlRow(new ControlSnapshot(rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getLong(4), Mode.valueOf(rs.getString(5)), rs.getBoolean(6)),
                        rs.getString(7)), NAMESPACE);
        if (result == null || result.snapshot.revision() < 0 || result.snapshot.memberCount() < 0)
            throw new IllegalStateException("Membership control is missing or corrupt");
        return result;
    }

    private Set<RouteAddress> checkedBatch(Collection<RouteAddress> addresses) {
        Objects.requireNonNull(addresses, "addresses");
        if (addresses.isEmpty() || addresses.size() > MAX_REGISTRATION_BATCH)
            throw new IllegalArgumentException("Membership registration must contain 1..500 addresses");
        Set<RouteAddress> result = new LinkedHashSet<>(addresses);
        if (result.contains(null)) throw new IllegalArgumentException("Null membership address");
        return result;
    }

    private void checkPermitOwner(RegistrationPermit permit) {
        if (permit == null || permit.issuer != issuer)
            throw new IllegalArgumentException("Registration permit belongs to another process or store");
    }

    private void checkDrainOwner(DrainPermit permit) {
        if (permit == null || permit.issuer != issuer)
            throw new IllegalArgumentException("Drain permit belongs to another process or store");
    }

    private void requireDrained(DrainPermit permit) {
        checkDrainOwner(permit);
        if (remainingDrainNanos(permit) != 0)
            throw new IllegalStateException("Membership negative leases have not drained");
    }

    private void checkDrainFence(DrainPermit permit, ControlRow control) {
        if (control.snapshot.mode() != Mode.DRAINING
                || !control.snapshot.generation().equals(permit.generation)
                || !control.transitionId.equals(permit.transitionId))
            throw new IllegalStateException("Membership maintenance token is stale");
    }

    private long remaining(long started) {
        long elapsed = nanoClock.getAsLong() - started;
        return elapsed < 0 ? PUBLICATION_DELAY_NANOS : Math.max(0, PUBLICATION_DELAY_NANOS - elapsed);
    }

    private record ControlRow(ControlSnapshot snapshot, String transitionId) { }
}
