package com.jupiter.shortlink.command.membership;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.batch.*;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.command.security.*;
import com.jupiter.shortlink.contract.Topics;
import com.jupiter.shortlink.id.*;
import com.jupiter.shortlink.membership.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real Command services and transactions; H2 replaces only the MySQL storage engine. */
class RoutePublicationBusinessTest {
    private final CommandPrincipal principal = new CommandPrincipal(1, "owner", 1);
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RoutePublicationCoordinator publication;
    private BusinessOutbox outbox;
    private LinkCommandService links;
    private BatchJobService jobs;
    private AtomicInteger allocations;
    private String gid;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("route-publication-h2.sql"))
                .execute(ds);
        jdbc =
                new JdbcTemplate(ds) {
                    @Override
                    public <T> T queryForObject(String sql, Class<T> type) {
                        if (sql.equals(
                                "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS"
                                        + " UNSIGNED)"))
                            return type.cast(System.currentTimeMillis());
                        return super.queryForObject(sql, type);
                    }
                };
        manager = new DataSourceTransactionManager(ds);
        jdbc.update(
                "INSERT INTO"
                    + " t_route_membership_control(namespace,generation,revision,member_count,mode,baseline_ready,transition_id)"
                    + " VALUES ('routes',?,0,0,'ENFORCE',TRUE,?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        jdbc.update(
                "INSERT INTO t_user(id,username,password) VALUES"
                        + " (1,'owner','fixture-only-password')");
        var auth = new CommandAuthorization(jdbc, "membership-test-internal-token-32-bytes");
        var groups = new GroupCommandService(jdbc, manager, auth, Clock.systemUTC());
        gid = groups.create(principal, "owned").gid();
        var limits = new BatchLimits(100000, 8, 2, 134217728, 67108864, 100000, 200, 2, 30000, 3);
        var quota = new TenantQuotaService(jdbc, limits);
        outbox = spy(new BusinessOutbox(jdbc, json, Clock.systemUTC()));
        publication =
                new RoutePublicationCoordinator(
                        new JdbcRouteMembershipStore(jdbc, manager), outbox, 8, 2, 15000);
        allocations = new AtomicInteger();
        AtomicLong next = new AtomicLong(1000);
        IdGenerator ids =
                new IdGenerator() {
                    public long nextId() {
                        throw new AssertionError("Must reserve Leaf ranges");
                    }

                    public List<IdRange> reserveRanges(int count) {
                        allocations.incrementAndGet();
                        long first = next.getAndAdd(count);
                        return List.of(new IdRange(first, first + count));
                    }

                    public void close() {}
                };
        links =
                new LinkCommandService(
                        jdbc,
                        manager,
                        auth,
                        groups,
                        ids,
                        new ShortCodeCodec(new byte[32]),
                        outbox,
                        json,
                        Clock.systemUTC(),
                        quota,
                        publication,
                        "s.example");
        jobs =
                new BatchJobService(
                        jdbc,
                        manager,
                        auth,
                        groups,
                        links,
                        quota,
                        ids,
                        outbox,
                        json,
                        Clock.systemUTC(),
                        limits,
                        mock(ImmutableImportStore.class));
    }

    @AfterEach
    void close() {
        if (publication != null) publication.close();
    }

    private LinkCommandService.Creation creation(int n) {
        return new LinkCommandService.Creation(
                "S.Example:443", "https://example.org/" + n, gid, 0, 0, null, "row " + n);
    }

    private List<LinkCommandService.Creation> rows(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(this::creation).toList();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private CountDownLatch observeRegistration() {
        CountDownLatch registered = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            Object value = call.callRealMethod();
                            if (Topics.ROUTE_MEMBERSHIP.equals(call.getArgument(1)))
                                registered.countDown();
                            return value;
                        })
                .when(outbox)
                .append(anyString(), anyString(), anyString(), any());
        return registered;
    }

    @Test
    void singleCreateRegistersBeforePublishingAndReplayDoesNotAllocate() throws Exception {
        CountDownLatch registered = observeRegistration();
        long start = System.nanoTime();
        var future = links.createManyAsync(principal, "single", rows(1));
        assertThat(registered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(future).isNotDone();
        assertThat(count("t_link_route")).isZero();
        var created = future.get(8, TimeUnit.SECONDS);
        assertThat(System.nanoTime() - start)
                .isGreaterThanOrEqualTo(JdbcRouteMembershipStore.PUBLICATION_DELAY_NANOS);
        assertThat(created).hasSize(1);
        assertThat(count("t_route_membership")).isEqualTo(1);
        assertThat(count("t_link_route")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT domain_norm FROM t_route_membership", String.class))
                .isEqualTo("s.example");
        assertThat(links.createManyAsync(principal, "single", rows(1)).get(2, TimeUnit.SECONDS))
                .isEqualTo(created);
        assertThat(allocations).hasValue(1);
    }

    @Test
    void synchronous500RowsShareOneRegistrationAndOneBarrier() throws Exception {
        assertThat(
                        links.createManyAsync(principal, "five-hundred", rows(500))
                                .get(10, TimeUnit.SECONDS))
                .hasSize(500);
        assertThat(count("t_route_membership")).isEqualTo(500);
        assertThat(count("t_link_route")).isEqualTo(500);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM t_route_membership_control", Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM t_outbox WHERE topic=?",
                                Long.class,
                                Topics.ROUTE_MEMBERSHIP))
                .isEqualTo(1);
        assertThat(allocations).hasValue(1);
    }

    @Test
    void exhaustedQuotaCannotAllocateOrRegisterButCommittedReplayStillWorks() throws Exception {
        jdbc.update("INSERT INTO t_tenant_quota(tenant_id,used_rows) VALUES (1,100000)");
        assertThatThrownBy(() -> links.createManyAsync(principal, "full", rows(1)).join())
                .hasRootCauseInstanceOf(
                        org.springframework.web.server.ResponseStatusException.class);
        assertThat(allocations).hasValue(0);
        assertThat(count("t_route_membership")).isZero();
        assertThat(count("t_link_route")).isZero();
        assertThat(count("t_outbox")).isZero();
        jdbc.update("UPDATE t_tenant_quota SET used_rows=0 WHERE tenant_id=1");
        var committed = links.createManyAsync(principal, "full", rows(1)).get(8, TimeUnit.SECONDS);
        jdbc.update("UPDATE t_tenant_quota SET used_rows=100000 WHERE tenant_id=1");
        assertThat(links.createManyAsync(principal, "full", rows(1)).get(2, TimeUnit.SECONDS))
                .isEqualTo(committed);
        assertThat(allocations).hasValue(1);
        assertThat(count("t_route_membership")).isEqualTo(1);
    }

    @Test
    void registrationOutboxFailureRollsBackMembershipAndNeverPublishes() {
        doThrow(new IllegalStateException("injected intent failure"))
                .when(outbox)
                .append(anyString(), eq(Topics.ROUTE_MEMBERSHIP), anyString(), any());
        assertThatThrownBy(() -> links.createManyAsync(principal, "register-fail", rows(1)).join())
                .hasRootCauseMessage("injected intent failure");
        assertThat(count("t_route_membership")).isZero();
        assertThat(count("t_link_route")).isZero();
        assertThat(count("t_outbox")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM t_route_membership_control", Long.class))
                .isZero();
    }

    @Test
    void businessRollbackKeepsRegistrationButRollsBackQuotaAndRoutes() {
        jdbc.update("INSERT INTO t_policy_resource(tenant_id,link_id) VALUES (1,1000)");
        assertThatThrownBy(() -> links.createManyAsync(principal, "business-fail", rows(1)).join())
                .isInstanceOf(CompletionException.class);
        assertThat(count("t_route_membership")).isEqualTo(1);
        assertThat(count("t_link_route")).isZero();
        assertThat(count("t_link")).isZero();
        assertThat(count("t_command_result")).isZero();
        assertThat(count("t_tenant_quota")).isZero();
        assertThat(count("t_outbox")).isEqualTo(1);
    }

    @Test
    void generationChangeDuringWaitRejectsPublication() throws Exception {
        CountDownLatch registered = observeRegistration();
        var future = links.createManyAsync(principal, "generation", rows(1));
        assertThat(registered.await(3, TimeUnit.SECONDS)).isTrue();
        jdbc.update(
                "UPDATE t_route_membership_control SET generation=?", UUID.randomUUID().toString());
        assertThatThrownBy(() -> future.get(8, TimeUnit.SECONDS))
                .hasRootCauseMessage(
                        "Membership publication permit is fenced by maintenance or generation");
        assertThat(count("t_link_route")).isZero();
    }

    @Test
    void directPublicationWithoutRegistrationPermitIsRejected() {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .execute(
                                                status ->
                                                        links.insertReservedMany(
                                                                principal,
                                                                rows(1),
                                                                List.of(new IdRange(1000, 1001)),
                                                                null)))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("t_link_route")).isZero();
    }

    @Test
    void cancellationAfterRouteSqlRollsBackTheWholeBusinessTransaction() throws Exception {
        CountDownLatch rowsWritten = new CountDownLatch(1),
                release = new CountDownLatch(1),
                rolledBack = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            Object value = call.callRealMethod();
                            org.springframework.transaction.support
                                    .TransactionSynchronizationManager.registerSynchronization(
                                    new org.springframework.transaction.support
                                            .TransactionSynchronization() {
                                        @Override
                                        public void afterCompletion(int status) {
                                            if (status == STATUS_ROLLED_BACK)
                                                rolledBack.countDown();
                                        }
                                    });
                            rowsWritten.countDown();
                            if (!release.await(3, TimeUnit.SECONDS))
                                throw new IllegalStateException("Test release timeout");
                            return value;
                        })
                .when(outbox)
                .appendMany(anyList());
        var future = links.createManyAsync(principal, "cancel-in-transaction", rows(1));
        try {
            assertThat(rowsWritten.await(5, TimeUnit.SECONDS)).isTrue();
            future.cancel(false);
        } finally {
            release.countDown();
        }
        assertThat(rolledBack.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(count("t_link_route")).isZero();
        assertThat(count("t_link")).isZero();
        assertThat(count("t_command_result")).isZero();
        assertThat(count("t_tenant_quota")).isZero();
        assertThat(count("t_route_membership")).isEqualTo(1);
        assertThat(count("t_outbox")).isEqualTo(1);
    }

    @Test
    void asynchronousChunkReusesDurableReservedIdsAfterRegistrationFailure() throws Exception {
        var submitted = jobs.submitInline(principal, "durable", rows(501));
        jobs.runAsync(jobs.claim(submitted.jobId(), "validator")).get(5, TimeUnit.SECONDS);
        doThrow(new IllegalStateException("membership unavailable"))
                .when(outbox)
                .append(anyString(), eq(Topics.ROUTE_MEMBERSHIP), anyString(), any());
        var first = jobs.claim(submitted.jobId(), "first");
        assertThatThrownBy(() -> jobs.runAsync(first).join())
                .hasRootCauseMessage("membership unavailable");
        List<Long> reserved =
                jdbc.queryForList(
                        "SELECT link_id FROM t_batch_row WHERE state='RESERVED' ORDER BY row_no",
                        Long.class);
        assertThat(reserved).hasSize(200);
        assertThat(count("t_link_route")).isZero();
        jobs.failed(first, new IllegalStateException("registration unavailable"));
        jdbc.update("UPDATE t_batch_job SET next_attempt_at=0 WHERE job_id=?", submitted.jobId());
        doCallRealMethod()
                .when(outbox)
                .append(anyString(), eq(Topics.ROUTE_MEMBERSHIP), anyString(), any());
        jobs.runAsync(jobs.claim(submitted.jobId(), "second")).get(8, TimeUnit.SECONDS);
        assertThat(
                        jdbc.queryForList(
                                "SELECT link_id FROM t_batch_row WHERE state='SUCCEEDED' ORDER BY"
                                        + " row_no",
                                Long.class))
                .isEqualTo(reserved);
        assertThat(count("t_route_membership")).isEqualTo(200);
        assertThat(count("t_link_route")).isEqualTo(200);
        assertThat(allocations).hasValue(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT used_rows FROM t_tenant_quota WHERE tenant_id=1",
                                Long.class))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reserved_rows FROM t_tenant_quota WHERE tenant_id=1",
                                Long.class))
                .isEqualTo(301);
    }

    @Test
    void asynchronousChunkCancellationDuringWaitRetainsIdsWithoutPublishing() throws Exception {
        var submitted = jobs.submitInline(principal, "cancel", rows(501));
        jobs.runAsync(jobs.claim(submitted.jobId(), "validator")).get(5, TimeUnit.SECONDS);
        CountDownLatch registered = observeRegistration();
        var future = jobs.runAsync(jobs.claim(submitted.jobId(), "creator"));
        assertThat(registered.await(3, TimeUnit.SECONDS)).isTrue();
        jobs.cancel(principal, submitted.jobId());
        assertThatThrownBy(() -> future.get(8, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(BatchJobService.StaleLeaseException.class);
        assertThat(count("t_link_route")).isZero();
        assertThat(count("t_route_membership")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reserved_rows FROM t_tenant_quota WHERE tenant_id=1",
                                Long.class))
                .isZero();
    }
}
