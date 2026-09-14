package com.jupiter.shortlink.command.batch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.config.CommandDataSourceConfiguration;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.metadata.MetadataJobService;
import com.jupiter.shortlink.command.metadata.SafeMetadataFetcher;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.command.security.*;
import com.jupiter.shortlink.id.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** Actual MySQL + actual ShardingSphere routes. Never targets an existing development database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchMetadataIntegrationTest {
    private HikariDataSource raw;
    private DataSource sharded;
    private CountingJdbc jdbc;
    private JdbcTemplate physical;
    private SegmentIdGenerator generator;
    private IdGenerator countingIds;
    private AtomicInteger allocations;
    private LinkCommandService links;
    private BatchJobService jobs;
    private MetadataJobService metadata;
    private GroupCommandService groups;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();
    private final CommandPrincipal principal = new CommandPrincipal(1, "batch-owner", 1);
    private BatchLimits limits;
    private byte[] objectInput;

    @BeforeAll
    void open() throws Exception {
        ((ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        String url = System.getenv("SHORTLINK_BATCH_TEST_JDBC_URL");
        assertTrue(
                url != null && !url.isBlank(), "Dedicated batch integration environment required");
        if (!"true".equals(System.getenv("SHORTLINK_BATCH_TEST_ALLOW_RESET"))
                || !url.matches("jdbc:mysql://[^/]+/shortlink_batch_it(?:\\?.*)?"))
            throw new IllegalArgumentException(
                    "Explicit shortlink_batch_it reset permission required");
        var config = new CommandDataSourceConfiguration();
        raw =
                config.physicalDataSource(
                        url,
                        System.getenv("SHORTLINK_BATCH_TEST_USER"),
                        System.getenv("SHORTLINK_BATCH_TEST_PASSWORD"));
        sharded = config.dataSource(raw);
        jdbc = new CountingJdbc(sharded);
        physical = new JdbcTemplate(raw);
    }

    @AfterAll
    void close() throws Exception {
        if (generator != null) generator.close();
        if (sharded instanceof AutoCloseable closeable) closeable.close();
        if (raw != null) raw.close();
    }

    @BeforeEach
    void fixture() {
        if (generator != null) generator.close();
        // This entire catalog was created solely for this suite by the root integrator.
        for (String table :
                List.of(
                        "t_batch_row",
                        "t_batch_job",
                        "t_metadata_job",
                        "t_tenant_quota",
                        "t_link_route",
                        "t_policy_resource",
                        "t_risk_policy",
                        "t_outbox",
                        "t_command_result",
                        "t_default_group_result",
                        "t_account_identity")) physical.update("DELETE FROM " + table);
        for (int i = 0; i < 16; i++)
            for (String table : List.of("t_link_", "t_group_", "t_user_"))
                physical.update("DELETE FROM " + table + i);
        physical.update("UPDATE t_id_alloc SET max_id=1 WHERE biz_tag='shortlink_global'");
        jdbc.update(
                "INSERT INTO t_account_identity(id,username,created_at) VALUES"
                        + " (1,'batch-owner',0)");
        jdbc.update(
                "INSERT INTO t_user(id,username,password) VALUES"
                        + " (1,'batch-owner','test-only-hash')");
        for (String gid : List.of("gA", "gB"))
            jdbc.update(
                    "INSERT INTO t_group(tenant_id,username,gid,name) VALUES (1,'batch-owner',?,?)",
                    gid,
                    gid);
        var manager = new DataSourceTransactionManager(sharded);
        var auth = new CommandAuthorization(jdbc, "01234567890123456789012345678901");
        limits = new BatchLimits(100000, 8, 2, 2 * 67108864L, 67108864, 100000, 200, 2, 30000, 3);
        var quota = new TenantQuotaService(jdbc, limits);
        groups = new GroupCommandService(jdbc, manager, auth, clock);
        generator =
                new SegmentIdGenerator(
                        new JdbcSegmentStore(
                                raw,
                                new JdbcOptions(
                                        "shortlink_batch_it",
                                        Duration.ofSeconds(2),
                                        Duration.ofSeconds(3),
                                        2)));
        allocations = new AtomicInteger();
        countingIds =
                new IdGenerator() {
                    public long nextId() {
                        throw new AssertionError("Batch must not call nextId");
                    }

                    public List<IdRange> reserveRanges(int count) {
                        allocations.incrementAndGet();
                        return generator.reserveRanges(count);
                    }

                    public void close() {}
                };
        var outbox = new BusinessOutbox(jdbc, json, clock);
        links =
                new LinkCommandService(
                        jdbc,
                        manager,
                        auth,
                        groups,
                        countingIds,
                        new ShortCodeCodec(new byte[32]),
                        outbox,
                        json,
                        clock,
                        quota,
                        new com.jupiter.shortlink.command.membership
                                .ExistingBusinessPublicationFixture(),
                        "s.example");
        ImmutableImportStore objects =
                new ImmutableImportStore() {
                    public void verifyReference(long tenant, Reference ref) {}

                    public InputStream open(Reference ref) {
                        return new ByteArrayInputStream(objectInput);
                    }
                };
        jobs =
                new BatchJobService(
                        jdbc,
                        manager,
                        auth,
                        groups,
                        links,
                        quota,
                        countingIds,
                        outbox,
                        json,
                        clock,
                        limits,
                        objects);
        metadata = new MetadataJobService(jdbc, manager, json, links, groups, outbox, clock);
        jdbc.failLinkBatch = false;
        jdbc.linkBatches = 0;
        jdbc.failMetadataDlq = false;
        jdbc.beforeMetadataFinish = null;
        jdbc.afterMetadataIdentityRead = null;
        jdbc.metadataFinishUpdates = 0;
        jdbc.metadataFinishAffected = -1;
    }

    @Test
    void membershipPublicationRetriesReservedIdentityThroughRealShardingSphere() throws Exception {
        physical.update("DELETE FROM t_route_membership");
        physical.update(
                "UPDATE t_route_membership_control SET"
                    + " generation=?,revision=0,member_count=0,mode='OFF',baseline_ready=FALSE",
                UUID.randomUUID().toString());
        var manager = new DataSourceTransactionManager(sharded);
        var auth = new CommandAuthorization(jdbc, "01234567890123456789012345678901");
        var quota = new TenantQuotaService(jdbc, limits);
        var outbox = new BusinessOutbox(jdbc, json, clock);
        try (var barrier =
                new com.jupiter.shortlink.command.membership.RoutePublicationCoordinator(
                        jdbc, manager, outbox, 8, 2, 15000)) {
            var registeredLinks =
                    new LinkCommandService(
                            jdbc,
                            manager,
                            auth,
                            groups,
                            countingIds,
                            new ShortCodeCodec(new byte[32]),
                            outbox,
                            json,
                            clock,
                            quota,
                            barrier,
                            "s.example");
            var registeredJobs =
                    new BatchJobService(
                            jdbc,
                            manager,
                            auth,
                            groups,
                            registeredLinks,
                            quota,
                            countingIds,
                            outbox,
                            json,
                            clock,
                            limits,
                            new ImmutableImportStore() {
                                public void verifyReference(long tenant, Reference ref) {}

                                public InputStream open(Reference ref) {
                                    return new ByteArrayInputStream(objectInput);
                                }
                            });
            var submitted = registeredJobs.submitInline(principal, "membership-retry", rows(501));
            registeredJobs
                    .runAsync(registeredJobs.claim(submitted.jobId(), "validator"))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            var first = registeredJobs.claim(submitted.jobId(), "first");
            jdbc.failLinkBatch = true;
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () ->
                            registeredJobs
                                    .runAsync(first)
                                    .get(10, java.util.concurrent.TimeUnit.SECONDS));
            List<Long> reserved =
                    physical.queryForList(
                            "SELECT link_id FROM t_batch_row WHERE state='RESERVED' ORDER BY"
                                + " row_no",
                            Long.class);
            assertEquals(200, reserved.size());
            assertEquals(200, count("t_route_membership"));
            assertEquals(0, count("t_link_route"));
            registeredJobs.failed(first, new IllegalStateException("injected route rollback"));
            physical.update(
                    "UPDATE t_batch_job SET next_attempt_at=0 WHERE job_id=?", submitted.jobId());
            jdbc.failLinkBatch = false;
            registeredJobs
                    .runAsync(registeredJobs.claim(submitted.jobId(), "retry"))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(
                    reserved,
                    physical.queryForList(
                            "SELECT link_id FROM t_batch_row WHERE state='SUCCEEDED' ORDER BY"
                                + " row_no",
                            Long.class));
            assertEquals(1, allocations.get());
            assertEquals(200, count("t_route_membership"));
            assertEquals(200, count("t_link_route"));
            assertEquals(
                    1L,
                    physical.queryForObject(
                            "SELECT COUNT(*) FROM t_outbox WHERE"
                                + " topic='shortlink.route.membership.v1'",
                            Long.class));
        }
    }

    private LinkCommandService.Creation row(int i) {
        return new LinkCommandService.Creation(
                "s.example", "https://example.org/" + i, "gA", 0, 0, null, "row " + i);
    }

    private List<LinkCommandService.Creation> rows(int size) {
        List<LinkCommandService.Creation> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) rows.add(row(i));
        return rows;
    }

    private long count(String table) {
        return Objects.requireNonNull(
                physical.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }

    private void run(String job) throws Exception {
        var lease = jobs.claim(job, UUID.randomUUID().toString());
        assertNotNull(lease);
        jobs.run(lease);
    }

    @Test
    void synchronousBatchIsAtomicRealBatchAndReplayDoesNotAllocate() {
        var input = rows(500);
        var result = links.createMany(principal, "sync", input);
        assertEquals(500, result.size());
        assertEquals(1, jdbc.linkBatches);
        assertEquals(1, allocations.get());
        assertEquals(500, count("t_link_route"));
        assertEquals(1000, count("t_outbox"));
        assertEquals(result, links.createMany(principal, "sync", input));
        assertEquals(1, allocations.get());
        assertThrows(
                ResponseStatusException.class, () -> links.createMany(principal, "sync", rows(2)));
        assertEquals(1, allocations.get());
        assertEquals(
                500,
                physical.queryForObject(
                        "SELECT used_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
    }

    @Test
    void failedSynchronousChunkRollsBackRoutesDetailsQuotaAndOutbox() {
        jdbc.failLinkBatch = true;
        assertThrows(
                DataIntegrityViolationException.class,
                () -> links.createMany(principal, "rollback", rows(2)));
        assertEquals(0, count("t_link_route"));
        assertEquals(0, count("t_outbox"));
        assertEquals(0, count("t_command_result"));
        assertEquals(0, count("t_tenant_quota"));
        assertEquals(2, links.createMany(principal, "rollback", rows(2)).size());
    }

    @Test
    void validationPrecedesQuotaIdsAndWorkerCommitsDurableRows() throws Exception {
        var status = jobs.submitInline(principal, "job", rows(501));
        assertEquals("VALIDATING", status.state());
        assertEquals(0, allocations.get());
        assertEquals(
                0,
                physical.queryForObject(
                        "SELECT reserved_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        assertThrows(ResponseStatusException.class, () -> groups.delete(principal, "gA"));
        run(status.jobId());
        assertEquals("READY", jobs.get(principal, status.jobId()).state());
        assertEquals(0, allocations.get());
        assertEquals(501, count("t_batch_row"));
        for (int i = 0; i < 3; i++) run(status.jobId());
        var done = jobs.get(principal, status.jobId());
        assertEquals("SUCCEEDED", done.state());
        assertEquals(501, done.succeededRows());
        assertEquals(501, count("t_link_route"));
        assertEquals(3, jdbc.linkBatches);
        assertEquals(
                0,
                physical.queryForObject(
                        "SELECT reserved_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        assertEquals(
                501,
                physical.queryForObject(
                        "SELECT used_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        assertEquals(
                0,
                groups.list(principal).stream()
                        .filter(g -> g.gid().equals("gA"))
                        .findFirst()
                        .orElseThrow()
                        .jobRefs());
        assertEquals(status.jobId(), jobs.submitInline(principal, "job", rows(501)).jobId());
        assertEquals(3, allocations.get());
    }

    @Test
    void failedBusinessChunkRetainsIdentityAndTakeoverRejectsOldFence() throws Exception {
        var status = jobs.submitInline(principal, "recover", rows(501));
        run(status.jobId());
        var old = jobs.claim(status.jobId(), "old");
        assertNotNull(old);
        jdbc.failLinkBatch = true;
        assertThrows(DataIntegrityViolationException.class, () -> jobs.run(old));
        assertEquals(0, count("t_link_route"));
        assertEquals(
                200,
                physical.queryForObject(
                        "SELECT COUNT(*) FROM t_batch_row WHERE link_id IS NOT NULL", Long.class));
        long first =
                physical.queryForObject(
                        "SELECT link_id FROM t_batch_row WHERE row_no=1", Long.class);
        physical.update("UPDATE t_batch_job SET lease_until=0 WHERE job_id=?", status.jobId());
        var replacement = jobs.claim(status.jobId(), "new");
        assertNotNull(replacement);
        assertThrows(BatchJobService.StaleLeaseException.class, () -> jobs.run(old));
        jobs.run(replacement);
        assertEquals(1, allocations.get());
        assertEquals(
                first,
                physical.queryForObject(
                        "SELECT link_id FROM t_batch_row WHERE row_no=1", Long.class));
        assertEquals(200, count("t_link_route"));
    }

    @Test
    void invalidAsyncRowsHaveExplicitResultsAndNeverConsumeLinkQuota() throws Exception {
        var input = rows(501);
        input.set(
                10,
                new LinkCommandService.Creation(
                        "s.example", "file:///private", "gA", 0, 0, null, "invalid"));
        var status = jobs.submitInline(principal, "mixed", input);
        run(status.jobId());
        assertEquals(1, jobs.get(principal, status.jobId()).invalidRows());
        for (int i = 0; i < 3; i++) run(status.jobId());
        assertEquals(500, jobs.get(principal, status.jobId()).succeededRows());
        assertEquals(
                500,
                physical.queryForObject(
                        "SELECT used_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        var invalid = jobs.results(principal, status.jobId(), 10, 1).get(0);
        assertEquals("INVALID", invalid.state());
        assertNull(invalid.linkId());
        assertEquals("INVALID_CREATION", invalid.error());
    }

    @Test
    void concurrentDuplicateAdmissionCountsOneJobAndValidationQuotaIsAtomic() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> jobs.submitInline(principal, "duplicate", rows(501)));
            var two = executor.submit(() -> jobs.submitInline(principal, "duplicate", rows(501)));
            assertEquals(
                    one.get(10, java.util.concurrent.TimeUnit.SECONDS).jobId(),
                    two.get(10, java.util.concurrent.TimeUnit.SECONDS).jobId());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("t_batch_job"));
        assertEquals(
                1,
                physical.queryForObject(
                        "SELECT validation_jobs FROM t_tenant_quota WHERE tenant_id=1",
                        Long.class));
        jobs.submitInline(principal, "second", rows(501));
        assertThrows(
                ResponseStatusException.class,
                () -> jobs.submitInline(principal, "over-budget", rows(501)));
        assertEquals(2, count("t_batch_job"));
        assertEquals(
                2,
                physical.queryForObject(
                        "SELECT active_jobs FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        assertEquals(0, allocations.get());
        assertEquals(1, jobs.candidates(8).size());
    }

    @Test
    void cancelFencesWorkerAndReleasesOnlyUnconsumedQuota() throws Exception {
        var status = jobs.submitInline(principal, "cancel", rows(501));
        run(status.jobId());
        run(status.jobId());
        var lease = jobs.claim(status.jobId(), "late");
        var cancelled = jobs.cancel(principal, status.jobId());
        assertEquals("CANCELLED", cancelled.state());
        assertThrows(BatchJobService.StaleLeaseException.class, () -> jobs.run(lease));
        assertEquals(200, count("t_link_route"));
        assertEquals(
                200,
                physical.queryForObject(
                        "SELECT used_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        assertEquals(
                0,
                physical.queryForObject(
                        "SELECT reserved_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        assertEquals("CANCELLED", jobs.results(principal, status.jobId(), 200, 1).get(0).state());
    }

    @Test
    void expiredUnstartedJobReleasesValidationBudgetAndGroupReference() {
        var status = jobs.submitInline(principal, "expire", rows(501));
        physical.update("UPDATE t_batch_job SET created_at=0 WHERE job_id=?", status.jobId());
        assertNull(jobs.claim(status.jobId(), "expired"));
        assertEquals("FAILED", jobs.get(principal, status.jobId()).state());
        assertEquals("JOB_EXPIRED", jobs.get(principal, status.jobId()).error());
        assertEquals(
                0,
                physical.queryForObject(
                        "SELECT validation_bytes FROM t_tenant_quota WHERE tenant_id=1",
                        Long.class));
        assertEquals(0, allocations.get());
        groups.delete(principal, "gA");
    }

    @Test
    void metadataLeaseTakeoverAndFinalFailureCannotOverwriteNewerWork() throws Exception {
        var created = links.createMany(principal, "metadata-failure", List.of(row(1))).get(0);
        long id = created.linkId();
        metadata.accept(
                json.writeValueAsString(
                        new MetadataJobService.Event(
                                "fail-event",
                                1,
                                "1",
                                id,
                                1,
                                links.digest(row(1).originUrl()),
                                row(1).originUrl())),
                MetadataJobService.TOPIC,
                0,
                9);
        String job = metadata.candidates(1).get(0);
        var old = metadata.claim(job, "old");
        physical.update("UPDATE t_metadata_job SET lease_until=0,attempts=7 WHERE job_id=?", job);
        var last = metadata.claim(job, "last");
        assertNotNull(last);
        var detailBefore = metadataDetail(id, "gA");
        var routeBefore = metadataRoute(id);
        var jobBefore = metadataJob(job);
        assertThrows(
                MetadataJobService.StaleLeaseException.class,
                () -> metadata.complete(old, new SafeMetadataFetcher.Metadata("late", null)));
        assertEquals(detailBefore, metadataDetail(id, "gA"));
        assertEquals(routeBefore, metadataRoute(id));
        assertEquals(jobBefore, metadataJob(job));
        metadata.failed(last, new IOException("public origin unavailable"));
        assertEquals(
                "FAILED",
                physical.queryForObject(
                        "SELECT metadata_status FROM t_link_route WHERE link_id=?",
                        String.class,
                        id));
        assertEquals(
                "FAILED",
                physical.queryForObject(
                        "SELECT state FROM t_metadata_job WHERE job_id=?", String.class, job));
        assertEquals("ACTIVE", links.get(principal, id).state());
        assertEquals(1, links.get(principal, id).version());
    }

    @Test
    void fixedObjectChecksumMismatchCannotReserveQuotaOrIds() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 50001; i++) {
            bytes.writeBytes(json.writeValueAsBytes(row(i)));
            bytes.write('\n');
        }
        objectInput = bytes.toByteArray();
        var ref =
                new ImmutableImportStore.Reference(
                        "imports",
                        "imports/1/file",
                        "fixed-version",
                        "0".repeat(64),
                        objectInput.length);
        var status =
                jobs.submitImport(
                        principal, new BatchJobService.ImportRequest("bad-manifest", "gA", ref));
        var lease = jobs.claim(status.jobId(), "validate");
        var error =
                assertThrows(BatchJobService.InvalidImportException.class, () -> jobs.run(lease));
        jobs.failed(lease, error);
        assertEquals("FAILED", jobs.get(principal, status.jobId()).state());
        assertEquals(0, allocations.get());
        assertEquals(0, count("t_link_route"));
        assertEquals(
                0,
                physical.queryForObject(
                        "SELECT reserved_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        assertEquals(
                0,
                physical.queryForObject(
                        "SELECT validation_bytes FROM t_tenant_quota WHERE tenant_id=1",
                        Long.class));
    }

    @Test
    void metadataTargetRevisionAndCurrentGroupPreventStaleOverwrite() throws Exception {
        var created = links.createMany(principal, "metadata", List.of(row(1))).get(0);
        long id = created.linkId();
        String event =
                json.writeValueAsString(
                        new MetadataJobService.Event(
                                "event-a",
                                1,
                                "1",
                                id,
                                1,
                                links.digest(row(1).originUrl()),
                                row(1).originUrl()));
        metadata.accept(event, MetadataJobService.TOPIC, 0, 1);
        metadata.accept(event, MetadataJobService.TOPIC, 0, 1);
        assertEquals(1, count("t_metadata_job"));
        var stale = metadata.claim(metadata.candidates(1).get(0), "old");
        links.update(principal, id, 1, null, "https://example.org/B", null, "B");
        links.update(principal, id, 2, "gB", row(1).originUrl(), null, "A again");
        metadata.complete(
                stale, new SafeMetadataFetcher.Metadata("STALE", "https://example.org/old.ico"));
        assertEquals(
                "OBSOLETE",
                physical.queryForObject(
                        "SELECT state FROM t_metadata_job WHERE job_id=?",
                        String.class,
                        stale.jobId()));
        metadata.accept(
                json.writeValueAsString(
                        new MetadataJobService.Event(
                                "event-new",
                                1,
                                "1",
                                id,
                                3,
                                links.digest(row(1).originUrl()),
                                row(1).originUrl())),
                MetadataJobService.TOPIC,
                0,
                2);
        var current = metadata.claim(metadata.candidates(1).get(0), "new");
        metadata.complete(
                current,
                new SafeMetadataFetcher.Metadata("CURRENT", "https://example.org/current.ico"));
        assertEquals(
                "CURRENT",
                jdbc.queryForObject(
                        "SELECT title FROM t_link WHERE gid='gB' AND id=?", String.class, id));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_link WHERE gid='gA' AND id=?", Long.class, id));
        assertEquals(3, links.get(principal, id).targetRevision());
        assertEquals(3, links.get(principal, id).version());
    }

    @Test
    void permanentDeleteReleasesStockQuotaAndRetainsRouteTombstone() {
        long id = links.createMany(principal, "delete", List.of(row(0))).get(0).linkId();
        links.transition(principal, id, 1, "DISABLED");
        links.transition(principal, id, 2, "DELETED");
        assertEquals(
                0,
                physical.queryForObject(
                        "SELECT used_rows FROM t_tenant_quota WHERE tenant_id=1", Long.class));
        assertEquals("DELETED", links.get(principal, id).state());
        groups.delete(principal, "gA");
        assertEquals(1, count("t_link_route"));
    }

    @Test
    void validCreateIntentWithDisallowedFetchPortReachesTerminalFailure() throws Exception {
        var input =
                new LinkCommandService.Creation(
                        "s.example", "https://example.org:8443/page", "gA", 0, 0, null, "port");
        long id = links.createMany(principal, "metadata-port", List.of(input)).get(0).linkId();
        metadata.accept(
                json.writeValueAsString(
                        new MetadataJobService.Event(
                                "port-event",
                                1,
                                "1",
                                id,
                                1,
                                links.digest(input.originUrl()),
                                input.originUrl())),
                MetadataJobService.TOPIC,
                0,
                12);
        String job = metadata.candidates(1).get(0);
        metadata.failed(
                metadata.claim(job, "port-worker"),
                new IllegalArgumentException("METADATA_TARGET_DENIED"));
        assertEquals(
                "FAILED",
                physical.queryForObject(
                        "SELECT state FROM t_metadata_job WHERE job_id=?", String.class, job));
        assertEquals(
                "FAILED",
                physical.queryForObject(
                        "SELECT metadata_status FROM t_link_route WHERE link_id=?",
                        String.class,
                        id));
        assertEquals("ACTIVE", links.get(principal, id).state());
    }

    @Test
    void malformedMetadataInputHasDurableDeduplicatedRejection() {
        metadata.accept("not-json", MetadataJobService.TOPIC, 2, 91);
        metadata.accept("not-json", MetadataJobService.TOPIC, 2, 91);
        assertEquals(0, count("t_metadata_job"));
        assertEquals(1, count("t_outbox"));
        assertEquals(
                "shortlink.metadata.dlq.v1",
                physical.queryForObject("SELECT topic FROM t_outbox", String.class));
        assertFalse(
                physical.queryForObject("SELECT payload FROM t_outbox", String.class)
                        .contains("not-json"));
    }

    @Test
    void oneBoundedMetadataPollPersistsMixedJobsAndDlqAndReplayIsIdempotent() throws Exception {
        List<MetadataJobService.IntakeRecord> batch = new ArrayList<>();
        for (int n = 0; n < 16; n++) {
            String origin = "https://example.org/metadata-batch/" + n;
            String payload =
                    n % 4 == 0
                            ? "invalid-input"
                            : json.writeValueAsString(
                                    new MetadataJobService.Event(
                                            "metadata-" + n,
                                            1,
                                            "1",
                                            n + 1,
                                            1,
                                            links.digest(origin),
                                            origin));
            batch.add(new MetadataJobService.IntakeRecord(payload, MetadataJobService.TOPIC, 3, n));
        }
        assertEquals(new MetadataJobService.IntakeResult(12, 4), metadata.acceptBatch(batch));
        assertEquals(12, count("t_metadata_job"));
        assertEquals(4, count("t_outbox"));
        assertEquals(new MetadataJobService.IntakeResult(12, 4), metadata.acceptBatch(batch));
        assertEquals(12, count("t_metadata_job"));
        assertEquals(4, count("t_outbox"));
    }

    @Test
    void metadataDlqFailureRollsBackTheSamePollsValidJobsOnRealShardedJdbc() throws Exception {
        String origin = "https://example.org/metadata-atomic";
        String payload =
                json.writeValueAsString(
                        new MetadataJobService.Event(
                                "metadata-atomic", 1, "1", 1, 1, links.digest(origin), origin));
        var batch =
                List.of(
                        new MetadataJobService.IntakeRecord(
                                payload, MetadataJobService.TOPIC, 4, 0),
                        new MetadataJobService.IntakeRecord(
                                "invalid-input", MetadataJobService.TOPIC, 4, 1));
        jdbc.failMetadataDlq = true;
        assertThrows(DataIntegrityViolationException.class, () -> metadata.acceptBatch(batch));
        assertEquals(0, count("t_metadata_job"));
        assertEquals(0, count("t_outbox"));
        assertEquals(new MetadataJobService.IntakeResult(1, 1), metadata.acceptBatch(batch));
        assertEquals(1, count("t_metadata_job"));
        assertEquals(1, count("t_outbox"));
    }

    @Test
    @Timeout(20)
    void expiredMetadataLeaseCannotCompleteOrRescheduleTheExistingJob() throws Exception {
        var lease = leasedMetadata("expired-metadata");
        physical.update(
                "UPDATE t_metadata_job SET lease_until=? WHERE job_id=?",
                databaseMillis() - 1,
                lease.jobId());
        var routeBefore = metadataRoute(lease.linkId());
        var detailBefore = metadataDetail(lease.linkId(), "gA");
        var jobBefore = metadataJob(lease.jobId());

        assertThrows(
                MetadataJobService.StaleLeaseException.class,
                () ->
                        metadata.complete(
                                lease, new SafeMetadataFetcher.Metadata("EXPIRED", "expired.ico")));
        metadata.failed(lease, new IOException("Expired worker cannot reschedule"));

        assertEquals(routeBefore, metadataRoute(lease.linkId()));
        assertEquals(detailBefore, metadataDetail(lease.linkId(), "gA"));
        assertEquals(jobBefore, metadataJob(lease.jobId()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Timeout(20)
    void databaseLeaseExpiryAtTheFinalWriteRollsBackBothMetadataTables(boolean terminalFailure)
            throws Exception {
        var lease = leasedMetadata("final-lease-" + terminalFailure);
        var routeBefore = metadataRoute(lease.linkId());
        var detailBefore = metadataDetail(lease.linkId(), "gA");
        var jobBefore = metadataJob(lease.jobId());
        var entered = new AtomicInteger();
        jdbc.beforeMetadataFinish =
                () -> {
                    entered.incrementAndGet();
                    String provisional = terminalFailure ? "FAILED" : "READY";
                    // These reads use the actual ShardingSphere transaction. Prove both data writes
                    // have happened before the final job fence, rather than failing at the first
                    // check.
                    assertEquals(
                            provisional,
                            jdbc.queryForObject(
                                    "SELECT metadata_status FROM t_link WHERE gid='gA' AND id=?",
                                    String.class,
                                    lease.linkId()));
                    assertEquals(
                            provisional,
                            jdbc.queryForObject(
                                    "SELECT metadata_status FROM t_link_route WHERE link_id=?",
                                    String.class,
                                    lease.linkId()));

                    // Test-only SQL shortens this already locked job's deadline. Let real MySQL
                    // time
                    // pass it; the service clock and final UPDATE are not mocked. This edit must
                    // itself
                    // roll back along with the preceding detail and route writes.
                    long expiresAt = databaseMillis() + 200;
                    jdbc.update(
                            "UPDATE t_metadata_job SET lease_until=? WHERE job_id=?",
                            expiresAt,
                            lease.jobId());
                    long deadline =
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                    while (databaseMillis() <= expiresAt) {
                        if (System.nanoTime() >= deadline)
                            throw new AssertionError(
                                    "MySQL fixture clock did not cross the lease deadline");
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }
                };

        if (terminalFailure) {
            // failed deliberately swallows StaleLeaseException; it still must roll back all
            // attempted FAILED metadata writes when the terminal job UPDATE affects zero rows.
            metadata.failed(lease, new IllegalArgumentException("METADATA_TARGET_DENIED"));
        } else {
            assertThrows(
                    MetadataJobService.StaleLeaseException.class,
                    () ->
                            metadata.complete(
                                    lease,
                                    new SafeMetadataFetcher.Metadata(
                                            "MUST_ROLL_BACK", "rollback.ico")));
        }

        assertEquals(1, entered.get());
        assertEquals(1, jdbc.metadataFinishUpdates);
        assertEquals(0, jdbc.metadataFinishAffected);
        assertEquals(routeBefore, metadataRoute(lease.linkId()));
        assertEquals(detailBefore, metadataDetail(lease.linkId(), "gA"));
        assertEquals(jobBefore, metadataJob(lease.jobId()));
    }

    @Test
    @Timeout(20)
    void accountDisabledAfterFetchClaimMakesMetadataObsoleteWithoutTouchingLinkData()
            throws Exception {
        var lease = leasedMetadata("disabled-account-metadata");
        var routeBefore = metadataRoute(lease.linkId());
        var detailBefore = metadataDetail(lease.linkId(), "gA");
        jdbc.update(
                "UPDATE t_user SET disabled=1,auth_version=auth_version+1 WHERE username=? AND"
                        + " id=?",
                principal.username(),
                principal.tenantId());

        metadata.complete(lease, new SafeMetadataFetcher.Metadata("UNAUTHORIZED", "blocked.ico"));

        assertEquals("OBSOLETE", metadataJob(lease.jobId()).get("state"));
        assertEquals("RESOURCE_UNAVAILABLE", metadataJob(lease.jobId()).get("last_error"));
        assertEquals(routeBefore, metadataRoute(lease.linkId()));
        assertEquals(detailBefore, metadataDetail(lease.linkId(), "gA"));
    }

    @Test
    @Timeout(20)
    void recycledLinkCannotReceiveAnEarlierMetadataResult() throws Exception {
        var lease = leasedMetadata("recycled-metadata");
        links.transition(principal, lease.linkId(), 1, "DISABLED");
        var routeBefore = metadataRoute(lease.linkId());
        var detailBefore = metadataDetail(lease.linkId(), "gA");

        metadata.complete(lease, new SafeMetadataFetcher.Metadata("RECYCLED", "blocked.ico"));

        assertEquals("OBSOLETE", metadataJob(lease.jobId()).get("state"));
        assertEquals("RESOURCE_INACTIVE", metadataJob(lease.jobId()).get("last_error"));
        assertEquals(routeBefore, metadataRoute(lease.linkId()));
        assertEquals(detailBefore, metadataDetail(lease.linkId(), "gA"));
        assertThrows(ResponseStatusException.class, () -> groups.delete(principal, "gA"));
    }

    @Test
    @Timeout(20)
    void sameTargetMoveAndOldGroupDeletionResolveTheCurrentShardForMetadata() throws Exception {
        var lease = leasedMetadata("moved-metadata");
        var accountLocked = new java.util.concurrent.CountDownLatch(1);
        var identityRead = new java.util.concurrent.CountDownLatch(1);
        jdbc.afterMetadataIdentityRead = identityRead::countDown;
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var moving =
                    workers.submit(
                            () -> {
                                var transaction =
                                        new org.springframework.transaction.support
                                                .TransactionTemplate(
                                                new DataSourceTransactionManager(sharded));
                                transaction.setTimeout(5);
                                transaction.executeWithoutResult(
                                        status -> {
                                            jdbc.queryForObject(
                                                    "SELECT id FROM t_user WHERE username=? AND"
                                                            + " id=? FOR UPDATE",
                                                    Long.class,
                                                    principal.username(),
                                                    principal.tenantId());
                                            accountLocked.countDown();
                                            awaitMetadataBarrier(identityRead);
                                            // The metadata transaction has already read identity
                                            // before waiting for
                                            // this account lock. A REPEATABLE_READ worker would
                                            // retain the old gA view.
                                            links.update(
                                                    principal,
                                                    lease.linkId(),
                                                    1,
                                                    "gB",
                                                    null,
                                                    null,
                                                    "moved");
                                            groups.delete(principal, "gA");
                                        });
                            });
            assertTrue(accountLocked.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var completing =
                    workers.submit(
                            () ->
                                    metadata.complete(
                                            lease,
                                            new SafeMetadataFetcher.Metadata(
                                                    "CURRENT_GROUP", "current.ico")));
            moving.get(8, java.util.concurrent.TimeUnit.SECONDS);
            completing.get(8, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            identityRead.countDown();
            jdbc.afterMetadataIdentityRead = null;
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertEquals("COMPLETED", metadataJob(lease.jobId()).get("state"));
        assertEquals("CURRENT_GROUP", metadataDetail(lease.linkId(), "gB").get("title"));
        assertEquals("READY", metadataDetail(lease.linkId(), "gB").get("metadata_status"));
        assertEquals("READY", metadataRoute(lease.linkId()).get("metadata_status"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_link WHERE gid='gA' AND id=?",
                        Integer.class,
                        lease.linkId()));
        assertEquals(1, links.get(principal, lease.linkId()).targetRevision());
        assertEquals(2, links.get(principal, lease.linkId()).version());
        assertEquals("gB", links.get(principal, lease.linkId()).gid());
    }

    private static void awaitMetadataBarrier(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError(
                        "Metadata identity read did not reach the bounded barrier");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private MetadataJobService.Lease leasedMetadata(String requestId) throws Exception {
        long id = links.createMany(principal, requestId, List.of(row(1))).get(0).linkId();
        metadata.accept(
                json.writeValueAsString(
                        new MetadataJobService.Event(
                                requestId,
                                1,
                                "1",
                                id,
                                1,
                                links.digest(row(1).originUrl()),
                                row(1).originUrl())),
                MetadataJobService.TOPIC,
                0,
                100);
        var lease = metadata.claim(metadata.candidates(1).get(0), "metadata-integration-worker");
        assertNotNull(lease);
        return lease;
    }

    private long databaseMillis() {
        return Objects.requireNonNull(
                physical.queryForObject(
                        "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",
                        Long.class));
    }

    private Map<String, Object> metadataRoute(long id) {
        return physical.queryForMap(
                "SELECT"
                    + " current_gid,route_status,origin_url,target_revision,route_version,ownership_version,metadata_status"
                    + " FROM t_link_route WHERE link_id=?",
                id);
    }

    private Map<String, Object> metadataDetail(long id, String gid) {
        return jdbc.queryForMap(
                "SELECT title,favicon,metadata_status,target_revision,origin_url,del_flag FROM"
                        + " t_link WHERE gid=? AND id=?",
                gid,
                id);
    }

    private Map<String, Object> metadataJob(String job) {
        return physical.queryForMap(
                "SELECT"
                    + " state,fence,lease_owner,lease_until,attempts,next_attempt_at,last_error,updated_at"
                    + " FROM t_metadata_job WHERE job_id=?",
                job);
    }

    private static final class CountingJdbc extends JdbcTemplate {
        boolean failLinkBatch;
        boolean failMetadataDlq;
        int linkBatches;
        Runnable beforeMetadataFinish;
        volatile Runnable afterMetadataIdentityRead;
        int metadataFinishUpdates;
        int metadataFinishAffected = -1;

        CountingJdbc(DataSource ds) {
            super(ds);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... arguments) {
            var rows = super.queryForList(sql, arguments);
            if (sql.startsWith("SELECT username FROM t_account_identity WHERE id=?")) {
                Runnable hook = afterMetadataIdentityRead;
                if (hook != null) hook.run();
            }
            return rows;
        }

        @Override
        public int update(String sql, Object... arguments) {
            if (sql.startsWith("UPDATE t_metadata_job SET")
                    && sql.contains("AND state='RUNNING'")
                    && sql.contains("AND fence=?")) {
                metadataFinishUpdates++;
                Runnable hook = beforeMetadataFinish;
                beforeMetadataFinish = null;
                if (hook != null) hook.run();
                metadataFinishAffected = super.update(sql, arguments);
                return metadataFinishAffected;
            }
            return super.update(sql, arguments);
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> arguments, int[] types) {
            if (failMetadataDlq && sql.startsWith("INSERT INTO t_outbox")) {
                failMetadataDlq = false;
                super.batchUpdate(sql, arguments, types);
                throw new DataIntegrityViolationException("Injected metadata DLQ batch failure");
            }
            if (sql.startsWith("INSERT INTO t_link(")) {
                linkBatches++;
                if (failLinkBatch) {
                    failLinkBatch = false;
                    throw new DataIntegrityViolationException("Injected detail batch failure");
                }
            }
            return super.batchUpdate(sql, arguments, types);
        }
    }
}
