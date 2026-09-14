package com.jupiter.shortlink.membership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jupiter.shortlink.membership.MembershipFixture.address;
import static org.junit.jupiter.api.Assertions.*;

class JdbcRouteMembershipStoreTest {
    private MembershipFixture f;

    @BeforeEach void setup() { f = new MembershipFixture(); f.createSchema(); }

    @Test void registersOneBatchAtOneRevisionAndDeduplicatesWithoutClearingPendingEntries() {
        AtomicInteger notices = new AtomicInteger();
        RegistrationPermit first = f.store.register(List.of(address("a"), address("b"), address("a")),
                notice -> { assertEquals(2, notice.memberCount()); notices.incrementAndGet(); });
        assertEquals(1, first.revision());
        assertEquals(2, first.addresses().size());
        RegistrationPermit duplicate = f.store.register(List.of(address("a")), notice -> notices.incrementAndGet());
        assertEquals(first.cut(), duplicate.cut());
        assertEquals(1, notices.get());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM t_link_route", Integer.class));
        RegistryPage page = f.store.readPage(f.store.readControl(), 0, 500);
        assertEquals(List.of(address("a"), address("b")), page.entries().stream().map(RegistryEntry::address).toList());
        assertTrue(page.complete());
    }

    @Test void grantsNoPublicationUntilFullPostCommitGraceAndRequiresTheBusinessTransaction() {
        RegistrationPermit permit = f.store.register(List.of(address("ready")));
        assertEquals(JdbcRouteMembershipStore.PUBLICATION_DELAY_NANOS, f.store.publicationDelayNanos(permit));
        assertThrows(IllegalStateException.class, () -> f.business.execute(status -> {
            f.store.verifyForPublication(permit, List.of(address("ready"))); return null;
        }));
        f.clock.addAndGet(JdbcRouteMembershipStore.PUBLICATION_DELAY_NANOS - 1);
        assertEquals(1, f.store.publicationDelayNanos(permit));
        f.clock.incrementAndGet();
        assertThrows(IllegalStateException.class, () -> f.store.verifyForPublication(permit, List.of(address("ready"))));
        f.business.executeWithoutResult(status -> {
            f.store.verifyForPublication(permit, List.of(address("ready")));
            f.jdbc.update("INSERT INTO t_link_route(link_id,domain_norm,short_uri) VALUES (1,'s.example','ready')");
        });
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM t_link_route", Integer.class));
    }

    @Test void registrationAndOutboxCommitTogetherAndObserverFailureRollsBackBoth() {
        assertThrows(IllegalStateException.class, () -> f.store.register(List.of(address("broken")), notice -> {
            f.jdbc.update("INSERT INTO test_outbox VALUES (?)", notice.revision());
            throw new IllegalStateException("observer failed");
        }));
        assertEquals(0, f.store.readControl().memberCount());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM test_outbox", Integer.class));
        f.store.register(List.of(address("fixed")), notice -> f.jdbc.update("INSERT INTO test_outbox VALUES (?)", notice.revision()));
        assertEquals(1, f.store.readControl().revision());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM test_outbox", Integer.class));
    }

    @Test void outerBusinessRollbackDoesNotEraseTheIndependentRegistration() {
        f.business.executeWithoutResult(status -> {
            RegistrationPermit permit = f.store.register(List.of(address("pending")));
            f.advanceGrace();
            f.store.verifyForPublication(permit, List.of(address("pending")));
            f.jdbc.update("INSERT INTO t_link_route(link_id,domain_norm,short_uri) VALUES (1,'s.example','pending')");
            status.setRollbackOnly();
        });
        assertEquals(1, f.store.readControl().memberCount());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM t_link_route", Integer.class));
    }

    @Test void generationChangeInvalidatesPermitPageAndLeaseEvenIfRevisionLooksEqual() {
        f.enforceEmpty();
        RegistrationPermit permit = f.store.register(List.of(address("new")));
        ControlSnapshot cut = f.store.readControl();
        f.advanceGrace();
        f.jdbc.update("UPDATE t_route_membership_control SET generation=?", UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, () -> f.business.executeWithoutResult(status ->
                f.store.verifyForPublication(permit, List.of(address("new")))));
        assertThrows(IllegalStateException.class, () -> f.store.readPage(cut, 0, 10));
        assertTrue(f.store.acquireLease(cut.cut()).isEmpty());
    }

    @Test void pageCutRemainsCompleteAcrossLaterRegistrationButCannotRenewAnOldLease() {
        f.enforceEmpty();
        f.store.register(List.of(address("largeId"), address("pendingOldId")));
        ControlSnapshot cut = f.store.readControl();
        RegistryPage first = f.store.readPage(cut, 0, 1);
        f.store.register(List.of(address("tinyIdAfterSnapshot")));
        RegistryPage second = f.store.readPage(cut, first.nextOrdinal(), 1);
        assertTrue(second.complete());
        assertEquals(2, second.nextOrdinal());
        assertTrue(f.store.acquireLease(cut.cut()).isEmpty());
        assertTrue(f.store.acquireLease(f.store.readControl().cut()).isPresent());
    }

    @Test void pageGapAndCountMismatchFailClosed() {
        f.store.register(List.of(address("a"), address("b"), address("c")));
        ControlSnapshot cut = f.store.readControl();
        f.jdbc.update("DELETE FROM t_route_membership WHERE member_ordinal=2");
        assertThrows(IllegalStateException.class, () -> f.store.readPage(cut, 0, 10));
        assertThrows(IllegalStateException.class, () -> f.store.readPage(cut, 1, 1));
        f.jdbc.update("DELETE FROM t_route_membership WHERE member_ordinal=3");
        assertThrows(IllegalStateException.class, () -> f.store.readPage(cut, 1, 10));
    }

    @Test void forgedCutAndFutureEntryRevisionCannotCountAsComplete() {
        f.store.register(List.of(address("a")));
        ControlSnapshot cut = f.store.readControl();
        f.jdbc.update("UPDATE t_route_membership SET registration_revision=99");
        assertThrows(IllegalStateException.class, () -> f.store.readPage(cut, 0, 1));
        assertTrue(f.store.acquireLease(new AppliedCut(cut.generation(), 99, 1)).isEmpty());
    }

    @Test void leaseExpiresAtExactlyOneSecondAndCannotBorrowNetworkDelay() {
        f.enforceEmpty();
        AppliedCut cut = f.store.readControl().cut();
        DenialLease lease = f.store.acquireLease(cut).orElseThrow();
        f.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(999));
        assertTrue(lease.isValid());
        f.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertFalse(lease.isValid());
        AtomicInteger reads = new AtomicInteger();
        JdbcRouteMembershipStore delayed = new JdbcRouteMembershipStore(f.jdbc, f.manager,
                () -> reads.getAndIncrement() == 0 ? 10 : TimeUnit.SECONDS.toNanos(2));
        assertTrue(delayed.acquireLease(cut).isEmpty());
    }

    @Test void backwardsClockCannotCreateAValidLeaseOrSkipPublicationWaiting() {
        f.enforceEmpty();
        DenialLease lease = f.store.acquireLease(f.store.readControl().cut()).orElseThrow();
        RegistrationPermit permit = f.store.register(List.of(address("a")));
        f.clock.addAndGet(-TimeUnit.SECONDS.toNanos(100));
        assertFalse(lease.isValid());
        assertEquals(JdbcRouteMembershipStore.PUBLICATION_DELAY_NANOS, f.store.publicationDelayNanos(permit));
    }

    @Test void observerCommitTimeDoesNotCountTowardTheGracePeriod() {
        RegistrationPermit permit = f.store.register(List.of(address("a")), notice -> f.advanceGrace());
        assertEquals(JdbcRouteMembershipStore.PUBLICATION_DELAY_NANOS, f.store.publicationDelayNanos(permit));
    }

    @Test void registrationSerializesWithLeaseRenewalAndOldLeaseCannotOutlivePublicationGrace() throws Exception {
        f.enforceEmpty();
        AppliedCut before = f.store.readControl().cut();
        DenialLease oldLease = f.store.acquireLease(before).orElseThrow();
        CountDownLatch registrationLocked = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<RegistrationPermit> writing = CompletableFuture.supplyAsync(() ->
                    f.store.register(List.of(address("arrives")), notice -> {
                        registrationLocked.countDown(); await(releaseCommit);
                    }), pool);
            assertTrue(registrationLocked.await(2, TimeUnit.SECONDS));
            CompletableFuture<Boolean> renewing = CompletableFuture.supplyAsync(() -> f.store.acquireLease(before).isPresent(), pool);
            assertThrows(TimeoutException.class, () -> renewing.get(100, TimeUnit.MILLISECONDS));
            releaseCommit.countDown();
            RegistrationPermit permit = writing.get(2, TimeUnit.SECONDS);
            assertFalse(renewing.get(2, TimeUnit.SECONDS));
            assertTrue(oldLease.isValid());
            f.advanceGrace();
            assertFalse(oldLease.isValid());
            f.business.executeWithoutResult(status -> f.store.verifyForPublication(permit, List.of(address("arrives"))));
        } finally { releaseCommit.countDown(); pool.shutdownNow(); }
    }

    @Test void publicationHoldsGenerationAndMaintenanceFenceUntilTheBusinessCommit() throws Exception {
        RegistrationPermit permit = f.store.register(List.of(address("held")));
        f.advanceGrace();
        CountDownLatch verified = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> publishing = CompletableFuture.runAsync(() -> f.business.executeWithoutResult(status -> {
                f.store.verifyForPublication(permit, List.of(address("held")));
                verified.countDown(); await(releaseCommit);
                f.jdbc.update("INSERT INTO t_link_route(link_id,domain_norm,short_uri) VALUES (1,'s.example','held')");
            }), pool);
            assertTrue(verified.await(2, TimeUnit.SECONDS));
            CompletableFuture<DrainPermit> draining = CompletableFuture.supplyAsync(f.store::beginDrain, pool);
            assertThrows(TimeoutException.class, () -> draining.get(100, TimeUnit.MILLISECONDS));
            releaseCommit.countDown();
            publishing.get(2, TimeUnit.SECONDS);
            draining.get(2, TimeUnit.SECONDS);
            assertEquals(Mode.DRAINING, f.store.readControl().mode());
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM t_link_route", Integer.class));
        } finally { releaseCommit.countDown(); pool.shutdownNow(); }
    }

    @Test void baselineIncludesEveryRouteStateAndPreviouslyPendingRegistrations() {
        f.store.register(List.of(address("pending")));
        f.jdbc.update("INSERT INTO t_link_route(link_id,domain_norm,short_uri,route_status) VALUES (1,'s.example','expired','EXPIRED')");
        f.jdbc.update("INSERT INTO t_link_route(link_id,domain_norm,short_uri,route_status) VALUES (2,'s.example','disabled','DISABLED')");
        DrainPermit drain = f.store.beginDrain();
        assertThrows(IllegalStateException.class, () -> f.store.register(List.of(address("blocked"))));
        assertThrows(IllegalStateException.class, () -> f.store.completeBaseline(drain));
        f.advanceGrace();
        assertThrows(IllegalStateException.class, () -> f.store.completeBaseline(drain));
        f.store.bootstrapPage(drain, List.of(address("expired"), address("disabled")));
        ControlSnapshot ready = f.store.completeBaseline(drain);
        assertEquals(3, ready.memberCount());
        assertTrue(ready.baselineReady());
        assertEquals(Mode.SHADOW, ready.mode());
        assertTrue(f.store.acquireLease(ready.cut()).isEmpty());
    }

    @Test void offRequiresDrainAndInvalidatesBaselineUntilExplicitReinitialization() {
        f.enforceEmpty();
        DenialLease oldLease = f.store.acquireLease(f.store.readControl().cut()).orElseThrow();
        DrainPermit drain = f.store.beginDrain();
        assertTrue(f.store.acquireLease(f.store.readControl().cut()).isEmpty());
        assertThrows(IllegalStateException.class, () -> f.store.finishDrain(drain, Mode.OFF));
        assertTrue(oldLease.isValid());
        f.advanceGrace();
        assertFalse(oldLease.isValid());
        assertFalse(f.store.finishDrain(drain, Mode.OFF).baselineReady());
        DrainPermit attemptedEnable = f.store.beginDrain();
        f.advanceGrace();
        assertThrows(IllegalStateException.class, () -> f.store.finishDrain(attemptedEnable, Mode.ENFORCE));
    }

    @Test void staleMaintenanceAndForeignProcessPermitsFailClosed() {
        RegistrationPermit permit = f.store.register(List.of(address("a")));
        JdbcRouteMembershipStore other = new JdbcRouteMembershipStore(f.jdbc, f.manager, f.clock::get);
        assertThrows(IllegalArgumentException.class, () -> other.publicationDelayNanos(permit));
        DrainPermit stale = f.store.beginDrain();
        DrainPermit latest = f.store.beginDrain();
        f.advanceGrace();
        assertThrows(IllegalStateException.class, () -> f.store.finishDrain(stale, Mode.OFF));
        assertEquals(Mode.OFF, f.store.finishDrain(latest, Mode.OFF).mode());
    }

    @Test void mismatchedTransactionManagerCannotSilentlyAutocommitTheControlLock() {
        MembershipFixture wrongDatabase = new MembershipFixture();
        wrongDatabase.createSchema();
        JdbcRouteMembershipStore mismatched = new JdbcRouteMembershipStore(f.jdbc, wrongDatabase.manager, f.clock::get);
        assertThrows(IllegalStateException.class, () -> mismatched.register(List.of(address("broken"))));
        assertThrows(IllegalStateException.class, () -> mismatched.readPage(f.store.readControl(), 0, 1));
        assertThrows(IllegalStateException.class, () -> mismatched.acquireLease(f.store.readControl().cut()));
        assertThrows(IllegalStateException.class, mismatched::beginDrain);
        assertEquals(0, f.store.readControl().memberCount());
    }

    @Test void rejectsUnregisteredAddressOrLostRegistrationAtFinalPublication() {
        RegistrationPermit permit = f.store.register(List.of(address("a")));
        f.advanceGrace();
        assertThrows(IllegalArgumentException.class, () -> f.business.executeWithoutResult(status ->
                f.store.verifyForPublication(permit, List.of(address("b")))));
        f.jdbc.update("DELETE FROM t_route_membership");
        assertThrows(IllegalStateException.class, () -> f.business.executeWithoutResult(status ->
                f.store.verifyForPublication(permit, List.of(address("a")))));
    }

    @Test void enforcesBoundedBatchesAndPages() {
        assertThrows(IllegalArgumentException.class, () -> f.store.register(List.of()));
        List<RouteAddress> tooMany = new ArrayList<>();
        for (int i = 0; i <= 500; i++) tooMany.add(address("c" + i));
        assertThrows(IllegalArgumentException.class, () -> f.store.register(tooMany));
        f.store.register(tooMany.subList(0, 500));
        assertEquals(500, f.store.readPage(f.store.readControl(), 0, 500).entries().size());
        assertThrows(IllegalArgumentException.class, () -> f.store.readPage(f.store.readControl(), 0, 1001));
        assertThrows(IllegalArgumentException.class, () -> f.store.readPage(f.store.readControl(), -1, 1));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }
}
