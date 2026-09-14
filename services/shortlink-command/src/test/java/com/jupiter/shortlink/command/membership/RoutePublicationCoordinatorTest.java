package com.jupiter.shortlink.command.membership;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.membership.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RoutePublicationCoordinatorTest {
    private JdbcTemplate jdbc;

    private JdbcRouteMembershipStore store() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("route-publication-h2.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO"
                    + " t_route_membership_control(namespace,generation,revision,member_count,mode,baseline_ready,transition_id)"
                    + " VALUES ('routes',?,0,0,'OFF',FALSE,?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        return new JdbcRouteMembershipStore(jdbc, new DataSourceTransactionManager(ds), () -> 0L);
    }

    private final RouteAddress address = new RouteAddress("s.example", "Ab123xy90");

    @Test
    void leaseWaitReleasesTheOnlyWorkerAndCancellationPreventsPublication() throws Exception {
        var store = store();
        var outbox = mock(BusinessOutbox.class);
        CountDownLatch registered = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            registered.countDown();
                            return null;
                        })
                .when(outbox)
                .append(anyString(), anyString(), anyString(), any());
        try (var coordinator = new RoutePublicationCoordinator(store, outbox, 2, 1, 4000)) {
            AtomicBoolean committed = new AtomicBoolean();
            var waiting =
                    coordinator.publish(
                            () ->
                                    new RoutePublication.Prepared<>(
                                            List.of(address),
                                            token -> {
                                                committed.set(true);
                                                return "new";
                                            }));
            assertThat(registered.await(1, TimeUnit.SECONDS)).isTrue();
            // With one executor thread, this unrelated replay completes while the first waits.
            var replay =
                    coordinator.publish(
                            () -> new RoutePublication.Prepared<>(List.of(), token -> "existing"));
            assertThat(replay.get(500, TimeUnit.MILLISECONDS)).isEqualTo("existing");
            waiting.cancel(false);
            assertThat(waiting).isCancelled();
            assertThat(committed).isFalse();
        }
    }

    @Test
    void boundedAdmissionRejectsBeforePreparingOrAllocatingMoreWork() throws Exception {
        var store = store();
        try (var coordinator =
                new RoutePublicationCoordinator(store, mock(BusinessOutbox.class), 1, 1, 4000)) {
            var first =
                    coordinator.publish(
                            () ->
                                    new RoutePublication.Prepared<>(
                                            List.of(address), token -> "first"));
            AtomicBoolean prepared = new AtomicBoolean();
            var second =
                    coordinator.publish(
                            () -> {
                                prepared.set(true);
                                return new RoutePublication.Prepared<>(
                                        List.of(), token -> "second");
                            });
            assertThatThrownBy(second::join)
                    .hasRootCauseMessage("503 SERVICE_UNAVAILABLE \"Route publication busy\"");
            assertThat(prepared).isFalse();
            first.cancel(false);
        }
    }

    @Test
    void deadlineStopsQueuedPreparationBeforeItCanRegisterOrCommit() throws Exception {
        var store = store();
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean registered = new AtomicBoolean();
        try (var coordinator =
                new RoutePublicationCoordinator(store, mock(BusinessOutbox.class), 2, 1, 2000)) {
            var first =
                    coordinator.publish(
                            () -> {
                                blocked.countDown();
                                try {
                                    release.await(4, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                return new RoutePublication.Prepared<>(
                                        List.of(), token -> "blocked");
                            });
            assertThat(blocked.await(1, TimeUnit.SECONDS)).isTrue();
            var second =
                    coordinator.publish(
                            () -> {
                                registered.set(true);
                                return new RoutePublication.Prepared<>(
                                        List.of(address), token -> "late");
                            });
            assertThatThrownBy(() -> second.get(3, TimeUnit.SECONDS))
                    .hasRootCauseMessage(
                            "503 SERVICE_UNAVAILABLE \"Route publication deadline exceeded\"");
            release.countDown();
            assertThat(first).isCompletedExceptionally();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_route_membership", Long.class))
                    .isZero();
            assertThat(registered).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void responseMappingPropagatesCancellationToPublication() {
        CompletableFuture<String> source = new CompletableFuture<>();
        var response = RoutePublication.map(source, String::length);
        response.cancel(false);
        assertThat(source).isCancelled();
    }
}
