package com.jupiter.shortlink.command.business;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.api.LinkCommandController;
import com.jupiter.shortlink.command.batch.*;
import com.jupiter.shortlink.command.config.CommandDataSourceConfiguration;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.outbox.*;
import com.jupiter.shortlink.command.risk.PolicyCommandService;
import com.jupiter.shortlink.command.security.*;
import com.jupiter.shortlink.id.*;
import com.jupiter.shortlink.risk.*;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

class CommandBusinessIntegrationTest {
    static HikariDataSource physical;
    static AutoCloseable sharded;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static final CommandPrincipal ALICE = new CommandPrincipal(101, "alice", 1);
    static final CommandPrincipal BOB = new CommandPrincipal(102, "bob", 1);
    static final String TOKEN = "integration-only-service-token-32-bytes";
    final MutableClock clock = new MutableClock();
    CommandAuthorization auth;
    GroupCommandService groups;
    LinkCommandService links;
    BusinessOutbox outbox;
    PolicyCommandService policies;
    String gid;
    AtomicLong sequence;
    IdGenerator ids;

    @BeforeAll
    static void connect() throws Exception {
        String url = System.getenv("SHORTLINK_BUSINESS_TEST_JDBC_URL");
        if (!"true".equals(System.getenv("SHORTLINK_BUSINESS_TEST_ALLOW_RESET"))
                || url == null
                || !url.matches(
                        "jdbc:mysql://127\\.0\\.0\\.1:[0-9]{4,5}/shortlink_business_it\\?.*")) {
            throw new IllegalStateException(
                    "Explicit isolated business integration URL and reset opt-in required");
        }
        int port = java.net.URI.create(url.substring(5)).getPort();
        if (port < 1024 || port > 65535)
            throw new IllegalStateException("Invalid isolated test port");
        var config = new CommandDataSourceConfiguration();
        physical =
                config.physicalDataSource(
                        url,
                        System.getenv("SHORTLINK_BUSINESS_TEST_USER"),
                        System.getenv("SHORTLINK_BUSINESS_TEST_PASSWORD"));
        javax.sql.DataSource dataSource = config.dataSource(physical);
        sharded = (AutoCloseable) dataSource;
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
    }

    @AfterAll
    static void close() throws Exception {
        if (sharded != null) sharded.close();
        if (physical != null) physical.close();
    }

    @BeforeEach
    void fixture() {
        JdbcTemplate raw = new JdbcTemplate(physical);
        for (String table :
                List.of(
                        "t_link_route",
                        "t_tenant_quota",
                        "t_outbox",
                        "t_command_result",
                        "t_default_group_result",
                        "t_policy_resource",
                        "t_risk_policy",
                        "t_analytics_epoch_history")) raw.update("DELETE FROM " + table);
        for (int i = 0; i < 16; i++)
            for (String table : List.of("t_user_", "t_group_", "t_link_"))
                raw.update("DELETE FROM " + table + i);
        raw.update(
                "UPDATE t_analytics_epoch SET"
                    + " recovery_epoch='UNINITIALIZED',actions_enabled=FALSE,gate_fence=0,updated_at=0"
                    + " WHERE singleton_id=1");
        jdbc.update(
                "INSERT INTO t_user(id,username,password,auth_version,disabled,del_flag) VALUES"
                    + " (101,'alice','unused-fixture-hash',1,FALSE,0),(102,'bob','unused-fixture-hash',1,FALSE,0)");
        auth = new CommandAuthorization(jdbc, TOKEN);
        groups = new GroupCommandService(jdbc, manager, auth, clock);
        ObjectMapper json = new ObjectMapper();
        outbox = new BusinessOutbox(jdbc, json, clock);
        var limits = new BatchLimits(1000000, 8, 2, 268435456, 67108864, 1000000, 200, 4, 30000, 8);
        sequence = new AtomicLong(1000);
        // Deterministic ID fault fixture; real allocator acceptance is in id-generator and batch
        // integration suites.
        ids =
                new IdGenerator() {
                    public long nextId() {
                        return sequence.getAndIncrement();
                    }

                    public List<IdRange> reserveRanges(int n) {
                        long start = sequence.getAndAdd(n);
                        return List.of(new IdRange(start, start + n));
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
                        clock,
                        new TenantQuotaService(jdbc, limits),
                        new com.jupiter.shortlink.command.membership
                                .ExistingBusinessPublicationFixture(),
                        "s.it.test");
        policies = new PolicyCommandService(jdbc, manager, auth, outbox, json, clock);
        gid = groups.create(ALICE, "first").gid();
    }

    @Test
    void membershipPublicationUsesActualShardedTransactionAndAsyncCreation() throws Exception {
        var raw = new JdbcTemplate(physical);
        raw.update("DELETE FROM t_route_membership");
        raw.update(
                "UPDATE t_route_membership_control SET"
                    + " generation=?,revision=0,member_count=0,mode='OFF',baseline_ready=FALSE",
                UUID.randomUUID().toString());
        try (var barrier =
                new com.jupiter.shortlink.command.membership.RoutePublicationCoordinator(
                        jdbc, manager, outbox, 8, 2, 15000)) {
            var registeredLinks =
                    new LinkCommandService(
                            jdbc,
                            manager,
                            auth,
                            groups,
                            ids,
                            new ShortCodeCodec(new byte[32]),
                            outbox,
                            new ObjectMapper(),
                            clock,
                            new TenantQuotaService(
                                    jdbc,
                                    new BatchLimits(
                                            1000000, 8, 2, 268435456, 67108864, 1000000, 200, 4,
                                            30000, 8)),
                            barrier,
                            "s.it.test");
            var input =
                    List.of(
                            new LinkCommandService.Creation(
                                    "S.IT.TEST:443",
                                    "https://example.org/membership",
                                    gid,
                                    0,
                                    0,
                                    null,
                                    "membership"));
            var result =
                    registeredLinks
                            .createManyAsync(ALICE, "registered", input)
                            .get(10, TimeUnit.SECONDS);
            assertThat(result).hasSize(1);
            assertThat(
                            raw.queryForObject(
                                    "SELECT COUNT(*) FROM t_route_membership WHERE"
                                        + " domain_norm='s.it.test'",
                                    Long.class))
                    .isEqualTo(1);
            assertThat(raw.queryForObject("SELECT COUNT(*) FROM t_link_route", Long.class))
                    .isEqualTo(1);
            assertThat(
                            raw.queryForObject(
                                    "SELECT COUNT(*) FROM t_outbox WHERE"
                                        + " topic='shortlink.route.membership.v1'",
                                    Long.class))
                    .isEqualTo(1);
            assertThat(
                            registeredLinks
                                    .createManyAsync(ALICE, "registered", input)
                                    .get(3, TimeUnit.SECONDS))
                    .isEqualTo(result);
            assertThat(sequence).hasValue(1001);
        }
    }

    LinkCommandService.Created create() {
        return links.createMany(
                        ALICE,
                        UUID.randomUUID().toString(),
                        List.of(
                                new LinkCommandService.Creation(
                                        "s.it.test",
                                        "https://example.org/path",
                                        gid,
                                        0,
                                        0,
                                        null,
                                        "fixture")))
                .get(0);
    }

    PolicyCommandService.Payload disabled() {
        return new PolicyCommandService.Payload(
                true, true, "Asia/Shanghai", List.of(), Set.of(), null);
    }

    PolicyCommandService.Mutation manual(
            long id,
            String policy,
            PolicyCommandService.Payload payload,
            String action,
            long revision) {
        return new PolicyCommandService.Mutation(
                UUID.randomUUID().toString(),
                id,
                policy,
                action,
                payload,
                clock.millis(),
                clock.millis() + 60000,
                revision,
                null,
                false);
    }

    @Test
    void creationTimePaginationDoesNotAssumeSegmentIdsAreChronological() {
        sequence.set(2000);
        long earlierCreatedAt = clock.millis();
        var earlier = create();
        clock.advance(1000);
        sequence.set(1000);
        long laterCreatedAt = clock.millis();
        var later = create();
        assertThat(earlier.linkId()).isGreaterThan(later.linkId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_at FROM t_link_route WHERE link_id=?",
                                Long.class,
                                earlier.linkId()))
                .isEqualTo(earlierCreatedAt);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_at FROM t_link_route WHERE link_id=?",
                                Long.class,
                                later.linkId()))
                .isEqualTo(laterCreatedAt);

        var request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Token", TOKEN);
        request.addHeader("x-shortlink-tenant-id", Long.toString(ALICE.tenantId()));
        request.addHeader("x-shortlink-username", ALICE.username());
        request.addHeader("x-shortlink-auth-version", Long.toString(ALICE.authVersion()));
        var controller = new LinkCommandController(links, groups, auth, jdbc, "s.it.test");
        var first = controller.page(gid, 1, 1, "createTime", request).data();
        var second = controller.page(gid, 2, 1, "createTime", request).data();
        assertThat(((Number) first.get("total")).longValue()).isEqualTo(2);
        assertThat(((List<?>) first.get("records")))
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(((Map<?, ?>) row).get("linkId"))
                                        .isEqualTo(later.linkId()));
        assertThat(((List<?>) second.get("records")))
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(((Map<?, ?>) row).get("linkId"))
                                        .isEqualTo(earlier.linkId()));
    }

    @Test
    void defaultGroupResultSurvivesRenameAndDeleteAndConcurrentRetry() throws Exception {
        String command = "account-default-group:101";
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++)
                futures.add(
                        pool.submit(() -> groups.defaultGroup(101, "alice", command, "default")));
            Set<String> results = new HashSet<>();
            for (var f : futures) results.add(f.get(8, TimeUnit.SECONDS));
            assertThat(results).hasSize(1);
            String created = results.iterator().next();
            groups.rename(ALICE, created, "renamed");
            groups.delete(ALICE, created);
            assertThat(groups.defaultGroup(101, "alice", command, "default")).isEqualTo(created);
            assertThat(groups.list(ALICE)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletionCompetesWithCreationUnderSameAccountAndGroupLocks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> create =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    create();
                                    return true;
                                } catch (ResponseStatusException e) {
                                    return false;
                                }
                            });
            Future<Boolean> delete =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    groups.delete(ALICE, gid);
                                    return true;
                                } catch (ResponseStatusException e) {
                                    return false;
                                }
                            });
            start.countDown();
            boolean c = create.get(8, TimeUnit.SECONDS), d = delete.get(8, TimeUnit.SECONDS);
            assertThat(c ^ d).isTrue();
            Long routeCount =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM t_link_route WHERE tenant_id=101", Long.class);
            assertThat(routeCount).isEqualTo(c ? 1L : 0L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void currentAccountVersionRejectsRevokedPrincipalAndForeignTenant() {
        var link = create();
        assertThatThrownBy(() -> links.get(BOB, link.linkId()))
                .isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE t_user SET auth_version=2 WHERE username='alice' AND id=101");
        assertThatThrownBy(
                        () ->
                                links.createMany(
                                        ALICE,
                                        "revoked",
                                        List.of(
                                                new LinkCommandService.Creation(
                                                        "s.it.test",
                                                        "https://example.org",
                                                        gid,
                                                        0,
                                                        0,
                                                        null,
                                                        null))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(sequence.get()).isEqualTo(1001L);
    }

    @Test
    void groupMoveHandlesTwoGidsInTheSamePhysicalShardAndPreservesIdentity() {
        var link = create();
        String sameShard = null;
        // HASH_MOD uses Java hashCode modulo 16. Build a distinct group with that exact shard.
        for (int i = 0; i < 1000; i++) {
            String candidate = "same-shard-" + i;
            if (Math.abs(candidate.hashCode()) % 16 == Math.abs(gid.hashCode()) % 16) {
                sameShard = candidate;
                break;
            }
        }
        assertThat(sameShard).isNotNull();
        jdbc.update(
                "INSERT INTO t_group(tenant_id,username,gid,name) VALUES (101,'alice',?,'same"
                        + " shard')",
                sameShard);
        links.update(ALICE, link.linkId(), 1, sameShard, null, null, "moved");
        var after = links.get(ALICE, link.linkId());
        assertThat(after.gid()).isEqualTo(sameShard);
        assertThat(after.shortUri()).isEqualTo(link.shortUri());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM t_link WHERE gid=? AND id=?",
                                Long.class,
                                gid,
                                link.linkId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM t_link WHERE gid=? AND id=?",
                                Long.class,
                                sameShard,
                                link.linkId()))
                .isEqualTo(1L);
    }

    @Test
    void restartWithDifferentShortCodeKeyCannotJoinExistingNamespace() {
        var raw = new JdbcTemplate(physical);
        raw.update("DELETE FROM t_id_namespace");
        var configuration = new com.jupiter.shortlink.command.config.IdConfiguration();
        var first = configuration.shortCodeCodec("00".repeat(32), physical);
        assertThat(configuration.shortCodeCodec("00".repeat(32), physical).encode(123))
                .isEqualTo(first.encode(123));
        assertThatThrownBy(() -> configuration.shortCodeCodec("01".repeat(32), physical))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("namespace");
        assertThat(raw.queryForObject("SELECT COUNT(*) FROM t_id_namespace", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void nonOverlappingPolicyWindowsDenyAndManualRevocationAlwaysAdvancesRevision() {
        var link = create();
        var early =
                new PolicyCommandService.Payload(
                        false,
                        false,
                        "Asia/Shanghai",
                        List.of(new AllowedTimeWindow(0, 100)),
                        Set.of(),
                        null);
        var late =
                new PolicyCommandService.Payload(
                        false,
                        false,
                        "Asia/Shanghai",
                        List.of(new AllowedTimeWindow(200, 300)),
                        Set.of(),
                        null);
        policies.activate(ALICE, manual(link.linkId(), "early", early, "ALLOW_TIME_WINDOW", 0));
        policies.activate(ALICE, manual(link.linkId(), "late", late, "ALLOW_TIME_WINDOW", 1));
        PolicySnapshot denied = policies.snapshot(101, link.linkId());
        assertThat(denied.timeUnrestricted()).isFalse();
        assertThat(denied.allowedWindows()).isEmpty();
        policies.activate(
                ALICE, manual(link.linkId(), "off-a", disabled(), "DISABLE_SHORT_LINK", 2));
        policies.activate(
                ALICE, manual(link.linkId(), "off-b", disabled(), "DISABLE_SHORT_LINK", 3));
        var result =
                policies.revoke(
                        ALICE,
                        new PolicyCommandService.Revocation(
                                "manual-revoke", link.linkId(), "off-a"));
        assertThat(result.policyRevision()).isEqualTo(5);
        assertThat(policies.snapshot(101, link.linkId()).disabled()).isTrue();
        assertThat(
                        policies.revoke(
                                ALICE,
                                new PolicyCommandService.Revocation(
                                        "manual-revoke", link.linkId(), "off-a")))
                .isEqualTo(result);
    }

    @Test
    void repeatableReadSnapshotCannotPairNewPolicyRevisionWithOldAllowedFacts() throws Exception {
        var link = create();
        CountDownLatch oldViewEstablished = new CountDownLatch(1),
                activationCommitted = new CountDownLatch(1);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<List<Object>> result =
                    reader.submit(
                            () -> {
                                var outer =
                                        new org.springframework.transaction.support
                                                .TransactionTemplate(manager);
                                outer.setIsolationLevel(
                                        org.springframework.transaction.TransactionDefinition
                                                .ISOLATION_REPEATABLE_READ);
                                outer.setTimeout(12);
                                return outer.execute(
                                        status -> {
                                            // Establish a real InnoDB consistent-read view before
                                            // another connection commits a restriction.
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT COUNT(*) FROM"
                                                                            + " t_risk_policy WHERE"
                                                                            + " tenant_id=? AND"
                                                                            + " link_id=?",
                                                                    Long.class,
                                                                    ALICE.tenantId(),
                                                                    link.linkId()))
                                                    .isZero();
                                            oldViewEstablished.countDown();
                                            try {
                                                if (!activationCommitted.await(8, TimeUnit.SECONDS))
                                                    throw new AssertionError(
                                                            "Concurrent activation did not finish");
                                            } catch (InterruptedException interrupted) {
                                                Thread.currentThread().interrupt();
                                                throw new AssertionError(interrupted);
                                            }
                                            PolicySnapshot snapshot =
                                                    policies.snapshot(
                                                            ALICE.tenantId(), link.linkId());
                                            var page =
                                                    policies.policyPage(ALICE, link.linkId(), null);
                                            return List.of(snapshot, page);
                                        });
                            });
            assertThat(oldViewEstablished.await(8, TimeUnit.SECONDS)).isTrue();
            var receipt =
                    policies.activate(
                            ALICE,
                            manual(
                                    link.linkId(),
                                    "committed-between-reads",
                                    disabled(),
                                    "DISABLE_SHORT_LINK",
                                    0));
            assertThat(receipt.status()).isEqualTo("COMMITTED");
            activationCommitted.countDown();
            List<Object> observed = result.get(8, TimeUnit.SECONDS);
            PolicySnapshot snapshot = (PolicySnapshot) observed.get(0);
            var page = (PolicyCommandService.PolicyPage) observed.get(1);
            assertThat(snapshot.policyRevision()).isEqualTo(receipt.policyRevision());
            assertThat(snapshot.disabled()).isTrue();
            assertThat(snapshot.state()).isEqualTo(PolicyState.KNOWN_RESTRICTED);
            assertThat(page.policyRevision()).isEqualTo(receipt.policyRevision());
            assertThat(page.policies())
                    .singleElement()
                    .satisfies(
                            fact -> {
                                assertThat(fact.policyId()).isEqualTo("committed-between-reads");
                                assertThat(fact.active()).isTrue();
                            });
        } finally {
            activationCommitted.countDown();
            reader.shutdownNow();
        }
    }

    @Test
    void expiryReconcilesUnderAuthorityAndCannotTurnAnotherRestrictionOff() {
        var link = create();
        long now = clock.millis();
        policies.activate(
                ALICE,
                new PolicyCommandService.Mutation(
                        "future",
                        link.linkId(),
                        "future",
                        "DISABLE_SHORT_LINK",
                        disabled(),
                        now + 100,
                        now + 1000,
                        0,
                        null,
                        false));
        assertThat(policies.snapshot(101, link.linkId()).state())
                .isEqualTo(PolicyState.KNOWN_ALLOWED);
        clock.advance(101);
        assertThat(policies.snapshot(101, link.linkId()).disabled()).isTrue();
        assertThat(policies.snapshot(101, link.linkId()).policyRevision()).isEqualTo(2);
        clock.advance(1000);
        assertThat(policies.snapshot(101, link.linkId()).state())
                .isEqualTo(PolicyState.KNOWN_ALLOWED);
        assertThat(policies.snapshot(101, link.linkId()).policyRevision()).isEqualTo(3);
    }

    @Test
    void automaticRetryReturnsCommittedResultAfterEvidenceExpiresAndEpochChanges() {
        var link = create();
        String epoch = "epoch-0000000000000001";
        long fence = policies.pause();
        policies.activateEpoch(epoch, fence);
        long now = clock.millis();
        var evidence = new PolicyCommandService.Evidence("snapshot-1", epoch, now, now, now + 1000);
        var command =
                new PolicyCommandService.Mutation(
                        "auto-once",
                        link.linkId(),
                        "auto-policy",
                        "DISABLE_SHORT_LINK",
                        disabled(),
                        now,
                        now + 30000,
                        0,
                        evidence,
                        true);
        var original = policies.activate(ALICE, command);
        assertThat(original.status()).isEqualTo("COMMITTED");
        clock.advance(2000);
        long newer = policies.pause();
        policies.activateEpoch("epoch-0000000000000002", newer);
        long outboxCount = jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox", Long.class);
        assertThat(policies.activate(ALICE, command)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox", Long.class))
                .isEqualTo(outboxCount);
        var stale =
                new PolicyCommandService.Mutation(
                        "auto-stale",
                        link.linkId(),
                        "other",
                        "DISABLE_SHORT_LINK",
                        disabled(),
                        now,
                        now + 30000,
                        1,
                        evidence,
                        true);
        assertThat(policies.activate(ALICE, stale).status()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox", Long.class))
                .isEqualTo(outboxCount);
    }

    @Test
    void lateEpochActivationAndEpochReuseCannotReopenAutomaticActions() {
        long first = policies.pause();
        long next = policies.pause();
        assertThatThrownBy(() -> policies.activateEpoch("epoch-0000000000000001", first))
                .isInstanceOf(ResponseStatusException.class);
        policies.activateEpoch("epoch-0000000000000002", next);
        policies.activateEpoch("epoch-0000000000000002", next);
        long third = policies.pause();
        assertThatThrownBy(() -> policies.activateEpoch("epoch-0000000000000002", third))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actions_enabled FROM t_analytics_epoch WHERE"
                                        + " singleton_id=1",
                                Boolean.class))
                .isFalse();
    }

    @Test
    void outboxLeaseFencesLateAcksAndRecoversAfterProcessLoss() {
        create();
        MockProducer<String, String> mock =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        try (OutboxAdapter adapter =
                new OutboxAdapter(new OutboxPublisher(jdbc, manager, clock, mock))) {
            var original = adapter.publisher.claim();
            assertThat(original).hasSize(2);
            clock.advance(31000);
            var renewed = adapter.publisher.claim();
            assertThat(renewed).hasSize(2);
            assertThat(adapter.publisher.ack(original.get(0))).isFalse();
            for (var claim : renewed) assertThat(adapter.publisher.ack(claim)).isTrue();
            assertThat(adapter.publisher.claim()).isEmpty();
        }
    }

    @Test
    void mutationDuringFrozenScopePaginationExpiresCursor() {
        create();
        var scope = new ResourceAuthorizationController(jdbc, auth);
        var original =
                scope.resolve(
                        ALICE,
                        new ResourceAuthorizationController.ResolveRequest(
                                gid, null, null, null, null));
        groups.rename(ALICE, gid, "changed");
        assertThatThrownBy(
                        () ->
                                scope.resolve(
                                        ALICE,
                                        new ResourceAuthorizationController.ResolveRequest(
                                                gid, null, null, 0L, original.ownershipVersion())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                scope.resolve(
                                        BOB,
                                        new ResourceAuthorizationController.ResolveRequest(
                                                gid, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    PolicyCommandService.Payload rate(int seconds) {
        return new PolicyCommandService.Payload(
                false, true, "Asia/Shanghai", List.of(), Set.of(), new RateLimitRule(100, seconds));
    }

    @Test
    void rateWindowRemainsHeldAfterRevocationSeveralIntervalsLater() {
        var link = create();
        long start = clock.millis();
        assertThat(start % 60000).isZero();
        policies.activate(
                ALICE,
                new PolicyCommandService.Mutation(
                        "rate-start",
                        link.linkId(),
                        "rate-old",
                        "LIMIT_RATE",
                        rate(60),
                        start,
                        start + 600000,
                        0,
                        null,
                        false));
        clock.advance(150000);
        var revoked =
                policies.revoke(
                        ALICE,
                        new PolicyCommandService.Revocation(
                                "rate-revoke", link.linkId(), "rate-old"));
        assertThat(revoked.policyRevision()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rate_window_lock_until FROM t_policy_resource WHERE"
                                        + " tenant_id=? AND link_id=?",
                                Long.class,
                                101,
                                link.linkId()))
                .isEqualTo(start + 180000);
        assertThatThrownBy(
                        () ->
                                policies.activate(
                                        ALICE,
                                        manual(
                                                link.linkId(),
                                                "rate-new",
                                                rate(5),
                                                "LIMIT_RATE",
                                                2)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Current rate budget window");
        clock.advance(30000);
        assertThat(
                        policies.activate(
                                        ALICE,
                                        manual(link.linkId(), "rate-new", rate(5), "LIMIT_RATE", 2))
                                .status())
                .isEqualTo("COMMITTED");
        assertThat(policies.snapshot(101, link.linkId()).rateLimit().windowSeconds()).isEqualTo(5);
    }

    @Test
    void naturalRateExpiryRetainsOnlyTheWindowThatActuallyHadTheRule() {
        var link = create();
        long start = clock.millis();
        policies.activate(
                ALICE,
                new PolicyCommandService.Mutation(
                        "rate-start",
                        link.linkId(),
                        "rate-old",
                        "LIMIT_RATE",
                        rate(60),
                        start,
                        start + 150000,
                        0,
                        null,
                        false));
        clock.advance(150001);
        var expired = policies.snapshot(101, link.linkId());
        assertThat(expired.rateLimit()).isNull();
        assertThat(expired.policyRevision()).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                policies.activate(
                                        ALICE,
                                        manual(
                                                link.linkId(),
                                                "rate-new",
                                                rate(5),
                                                "LIMIT_RATE",
                                                2)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Current rate budget window");
        clock.advance(29999);
        assertThat(
                        policies.activate(
                                        ALICE,
                                        manual(link.linkId(), "rate-new", rate(5), "LIMIT_RATE", 2))
                                .status())
                .isEqualTo("COMMITTED");
    }

    @Test
    void delayedNaturalExpiryDoesNotReserveAnUnconsumedLaterWindow() {
        var link = create();
        long start = clock.millis();
        policies.activate(
                ALICE,
                new PolicyCommandService.Mutation(
                        "rate-start",
                        link.linkId(),
                        "rate-old",
                        "LIMIT_RATE",
                        rate(60),
                        start,
                        start + 150000,
                        0,
                        null,
                        false));
        clock.advance(210000);
        var expired = policies.snapshot(101, link.linkId());
        assertThat(expired.rateLimit()).isNull();
        assertThat(expired.policyRevision()).isEqualTo(2);
        assertThat(
                        policies.activate(
                                        ALICE,
                                        manual(link.linkId(), "rate-new", rate(5), "LIMIT_RATE", 2))
                                .status())
                .isEqualTo("COMMITTED");
    }

    @Test
    void expiryExactlyAtWindowBoundaryDoesNotHoldTheFollowingWindow() {
        var link = create();
        long start = clock.millis();
        policies.activate(
                ALICE,
                new PolicyCommandService.Mutation(
                        "rate-start",
                        link.linkId(),
                        "rate-old",
                        "LIMIT_RATE",
                        rate(60),
                        start,
                        start + 180000,
                        0,
                        null,
                        false));
        clock.advance(180000);
        assertThat(policies.snapshot(101, link.linkId()).policyRevision()).isEqualTo(2);
        assertThat(
                        policies.activate(
                                        ALICE,
                                        manual(link.linkId(), "rate-new", rate(5), "LIMIT_RATE", 2))
                                .status())
                .isEqualTo("COMMITTED");
    }

    @Test
    void revokingFutureRateRuleDoesNotHoldAnUnusedCurrentWindow() {
        var link = create();
        long start = clock.millis();
        policies.activate(
                ALICE,
                new PolicyCommandService.Mutation(
                        "future-rate",
                        link.linkId(),
                        "rate-future",
                        "LIMIT_RATE",
                        rate(60),
                        start + 30000,
                        start + 300000,
                        0,
                        null,
                        false));
        policies.revoke(
                ALICE,
                new PolicyCommandService.Revocation("future-revoke", link.linkId(), "rate-future"));
        assertThat(
                        policies.activate(
                                        ALICE,
                                        manual(link.linkId(), "rate-now", rate(5), "LIMIT_RATE", 2))
                                .status())
                .isEqualTo("COMMITTED");
    }

    static class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(1788686400000L);

        void advance(long millis) {
            now.addAndGet(millis);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    }

    record OutboxAdapter(OutboxPublisher publisher) implements AutoCloseable {
        public void close() {
            publisher.close();
        }
    }
}
