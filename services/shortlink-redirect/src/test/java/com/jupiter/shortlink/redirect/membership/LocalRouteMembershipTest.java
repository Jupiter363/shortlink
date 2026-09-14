package com.jupiter.shortlink.redirect.membership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.membership.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class LocalRouteMembershipTest {
    final JdbcRouteMembershipStore store = mock(JdbcRouteMembershipStore.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final AtomicBoolean valid = new AtomicBoolean(true);
    final ControlSnapshot control =
            new ControlSnapshot("routes", "generation-a", 1, 2, Mode.ENFORCE, true);

    RouteMembershipProperties options(String snapshot, int pages) {
        return new RouteMembershipProperties(
                true, 100, .0001, .001, 1048576, 2, pages, 250, 10000, snapshot, false);
    }

    DenialLease lease(ControlSnapshot cut) {
        var lease = mock(DenialLease.class);
        when(lease.cut()).thenReturn(cut.cut());
        when(lease.isValid()).thenAnswer(ignored -> valid.get());
        when(lease.remainingNanos()).thenAnswer(ignored -> valid.get() ? 500_000_000L : 0L);
        return lease;
    }

    LocalRouteMembership populated() {
        when(store.readControl()).thenReturn(control);
        when(store.readPage(control, 0, 2))
                .thenReturn(
                        new RegistryPage(
                                control,
                                List.of(
                                        new RegistryEntry(
                                                1, 1, new RouteAddress("s.example", "Ab")),
                                        new RegistryEntry(
                                                2,
                                                1,
                                                new RouteAddress("s.example", "a".repeat(32)))),
                                2,
                                true));
        when(store.acquireLease(control.cut())).thenAnswer(ignored -> Optional.of(lease(control)));
        return new LocalRouteMembership(store, options("", 100), meters);
    }

    @Test
    void coldStateAndExpiredLeaseAlwaysFallThrough() {
        var membership = populated();
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                membership.check("s.example", "missing").outcome());
        membership.refresh();
        var absent = membership.check("s.example", "missing");
        assertEquals(RouteMembershipGuard.Outcome.DEFINITELY_ABSENT, absent.outcome());
        valid.set(false);
        assertFalse(membership.valid(absent.proof()));
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                membership.check("s.example", "missing").outcome());
    }

    @Test
    void existingHistoricalAddressesRemainPositiveAndCaseIsPreserved() {
        var membership = populated();
        membership.refresh();
        assertEquals(
                RouteMembershipGuard.Outcome.MAY_EXIST,
                membership.check("s.example", "Ab").outcome());
        assertEquals(
                RouteMembershipGuard.Outcome.MAY_EXIST,
                membership.check("s.example", "a".repeat(32)).outcome());
        assertEquals(
                RouteMembershipGuard.Outcome.DEFINITELY_ABSENT,
                membership.check("s.example", "ab").outcome());
    }

    @Test
    void renewedHolderCannotValidateAnOldNegativeEvenWithSameFilterAndCut() {
        var membership = populated();
        membership.refresh();
        var old = membership.check("s.example", "missing").proof();
        membership.refresh();
        assertFalse(membership.valid(old));
        assertTrue(membership.valid(membership.check("s.example", "missing").proof()));
        verify(store, times(1)).readPage(control, 0, 2);
    }

    @Test
    void caughtUpCutAndLeaseMustMatchExactly() {
        var membership = populated();
        var wrong = new ControlSnapshot("routes", "generation-b", 1, 2, Mode.ENFORCE, true);
        DenialLease wrongLease = lease(wrong);
        when(store.acquireLease(control.cut())).thenReturn(Optional.of(wrongLease));
        membership.refresh();
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                membership.check("s.example", "missing").outcome());
    }

    @Test
    void pageGapNeverPublishesOrAcquiresLease() {
        var membership = populated();
        when(store.readPage(control, 0, 2))
                .thenReturn(
                        new RegistryPage(
                                control,
                                List.of(
                                        new RegistryEntry(
                                                2, 1, new RouteAddress("s.example", "Ab"))),
                                2,
                                true));
        assertThrows(IllegalStateException.class, membership::refresh);
        verify(store, never()).acquireLease(any());
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                membership.check("s.example", "missing").outcome());
    }

    @Test
    void capacityFailureDoesNotDiscardMembersAndContinueDenying() {
        var membership = populated();
        membership.refresh();
        when(store.readControl())
                .thenReturn(
                        new ControlSnapshot("routes", "generation-a", 2, 101, Mode.ENFORCE, true));
        membership.refresh();
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                membership.check("s.example", "missing").outcome());
    }

    @Test
    void shadowCountsPredictedAbsenceButNeverDenies() {
        var shadow = new ControlSnapshot("routes", "generation-a", 0, 0, Mode.SHADOW, true);
        when(store.readControl()).thenReturn(shadow);
        var membership = new LocalRouteMembership(store, options("", 100), meters);
        membership.refresh();
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                membership.check("s.example", "missing").outcome());
        assertEquals(1, meters.counter("shortlink.redirect.membership.shadow.absent").count());
        verify(store, never()).acquireLease(any());
    }

    @Test
    void aLeaseReturningAfterStopCannotPublishADenialHolder() throws Exception {
        var membership = populated();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.acquireLease(control.cut()))
                .thenAnswer(
                        ignored -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return Optional.of(lease(control));
                        });
        var thread = new Thread(membership::refresh);
        thread.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            membership.stop();
            release.countDown();
            thread.join(5000);
            assertFalse(thread.isAlive());
            assertEquals(
                    RouteMembershipGuard.Outcome.UNKNOWN,
                    membership.check("s.example", "missing").outcome());
        } finally {
            release.countDown();
            thread.join(5000);
        }
    }

    @Test
    void snapshotRestoreRequiresNewLeaseAndCorruptSnapshotRebuilds(@TempDir Path directory)
            throws Exception {
        populated();
        var options = options(directory.resolve("bloom.bin").toString(), 100);
        var first = new LocalRouteMembership(store, options, meters);
        first.refresh();
        assertTrue(Files.exists(Path.of(options.snapshotPath())));
        clearInvocations(store);
        var restored = new LocalRouteMembership(store, options, new SimpleMeterRegistry());
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                restored.check("s.example", "missing").outcome());
        when(store.acquireLease(control.cut())).thenReturn(Optional.empty());
        restored.refresh();
        verify(store, never()).readPage(any(), anyLong(), anyInt());
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                restored.check("s.example", "missing").outcome());
        byte[] broken = Files.readAllBytes(Path.of(options.snapshotPath()));
        broken[broken.length - 1] ^= 1;
        Files.write(Path.of(options.snapshotPath()), broken);
        var recovered = new LocalRouteMembership(store, options, new SimpleMeterRegistry());
        recovered.refresh();
        verify(store).readPage(control, 0, 2);
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                recovered.check("s.example", "missing").outcome());
    }

    @Test
    void capacityConfigurationAccountsForConcurrentFiltersAndSnapshotCopies() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RouteMembershipProperties(
                                true,
                                10_000_000,
                                .0001,
                                .001,
                                32 * 1024 * 1024,
                                500,
                                100,
                                250,
                                10000,
                                "",
                                false));
    }
}
