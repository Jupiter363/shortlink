package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader.ReceiptProgress;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader.Snapshot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class JdbcCampaignResultProgressReaderTest {
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRY = NOW.plusSeconds(3600).toEpochMilli();

    @Test
    void realStagedReceiptsExposeOrderedCountsAndRejectCorruptOrUnassociatedReceipts() {
        Fixture fixture = fixture();
        RunToken run = createRun(fixture);
        fixture.steps().initialize(run, List.of(step("z-first"), step("a-second")));
        stage(fixture, run, "z-first", "child-z", "scope-zero-awaiting", 0, null);
        stage(fixture, run, "a-second", "child-second", "scope-zero-received", 0, 0);
        stage(fixture, run, "z-first", "child-a", "scope-partial", 501, 500);
        prepareChild(fixture.runs(), run, "z-first", "child-without-receipt");

        Snapshot snapshot = fixture.reader().read(OWNER, run.definition().runId());
        assertEquals(run, snapshot.execution().run().token());
        assertEquals(List.of(
                new ReceiptProgress("z-first", "scope-partial", "periods-frozen", EXPIRY, 1, 2, 500, 501),
                new ReceiptProgress("z-first", "scope-zero-awaiting", "periods-frozen", EXPIRY, 0, 1, 0, 0),
                new ReceiptProgress("a-second", "scope-zero-received", "periods-frozen", EXPIRY, 1, 1, 0, 0)),
                snapshot.receipts());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.receipts().clear());
        assertEquals(4, fixture.count("campaign_child_ledger"));
        assertEquals(3, fixture.count("campaign_statistics_receipt"));

        fixture.jdbc().update("UPDATE campaign_statistics_receipt SET stored_rows=502 WHERE child_id='child-a'");
        try {
            assertEquals("STATISTICS_PROGRESS_COUNTS_CORRUPTED", assertThrows(IllegalStateException.class,
                    () -> fixture.reader().read(OWNER, run.definition().runId())).getMessage());
        } finally {
            fixture.jdbc().update("UPDATE campaign_statistics_receipt SET stored_rows=500 WHERE child_id='child-a'");
        }
        String originalHash = fixture.jdbc().queryForObject(
                "SELECT spec_hash FROM campaign_statistics_receipt WHERE child_id='child-a'", String.class);
        fixture.jdbc().update("UPDATE campaign_statistics_receipt SET spec_hash=? WHERE child_id='child-a'", "0".repeat(64));
        try {
            assertEquals("STATISTICS_PROGRESS_RECEIPT_CORRUPTED", assertThrows(IllegalStateException.class,
                    () -> fixture.reader().read(OWNER, run.definition().runId())).getMessage());
        } finally {
            fixture.jdbc().update("UPDATE campaign_statistics_receipt SET spec_hash=? WHERE child_id='child-a'", originalHash);
        }
        assertEquals(snapshot, fixture.reader().read(OWNER, run.definition().runId()));
        stage(fixture, run, "missing-step", "child-orphan", "scope-orphan", 0, null);
        assertEquals("STATISTICS_PROGRESS_STEP_MISSING", assertThrows(IllegalStateException.class,
                () -> fixture.reader().read(OWNER, run.definition().runId()),
                "A registered receipt with no frozen step must fail instead of silently disappearing").getMessage());
    }

    @Test
    void readsEnforceExactCallerAndRemainAvailableAfterCancellationWithoutWritingAnyState() {
        Fixture fixture = fixture();
        RunToken run = createRun(fixture);
        fixture.steps().initialize(run, List.of(step("work")));
        stage(fixture, run, "work", "child-source", "scope-current", 501, 500);
        var before = fixture.databaseState();
        Snapshot active = fixture.reader().read(OWNER, run.definition().runId());
        assertEquals(run, active.execution().run().token());
        for (Caller foreign : List.of(new Caller("tenant-b", OWNER.subject(), OWNER.authVersion()),
                new Caller(OWNER.tenantId(), "analyst-b", OWNER.authVersion()),
                new Caller(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion() + 1))) {
            assertThrows(SecurityException.class, () -> fixture.reader().read(foreign, run.definition().runId()));
        }
        assertEquals(before, fixture.databaseState(), "Read and denied read must leave every existing table unchanged");

        fixture.runs().cancel(run);
        RunRecord cancelled = fixture.runs().loadRun(OWNER, run.definition().runId()).orElseThrow();
        var cancelledBefore = fixture.databaseState();
        Snapshot afterCancellation = fixture.reader().read(OWNER, run.definition().runId());
        assertEquals(cancelled, afterCancellation.execution().run());
        assertEquals(RunStatus.CANCELLED, afterCancellation.execution().run().status());
        assertEquals(active.execution().steps(), afterCancellation.execution().steps());
        assertEquals(active.receipts(), afterCancellation.receipts());
        assertEquals(cancelledBefore, fixture.databaseState());
    }

    @Test
    void currentRevisionUsesOnlyProgressColumnsAndProjectsExpiryWithoutLoadingPrivatePayloads() {
        Fixture fixture = fixture();
        RunToken old = createRun(fixture);
        fixture.steps().initialize(old, List.of(step("old-step")));
        stage(fixture, old, "old-step", "old-child", "scope-old", 1, 1);
        RunToken current = fixture.runs().revise(old, 2, "{\"revision\":2}");
        fixture.steps().initialize(current, List.of(step("current-step")));
        stage(fixture, current, "current-step", "current-child", "scope-current", 0, null);
        Clock expiredClock = Clock.fixed(Instant.ofEpochMilli(EXPIRY).plusSeconds(1), ZoneOffset.UTC);
        var before = fixture.databaseState();
        Snapshot snapshot;
        fixture.dataSource().auditProgressReads = true;
        try {
            snapshot = fixture.reader(expiredClock).read(OWNER, current.definition().runId());
        } finally {
            fixture.dataSource().auditProgressReads = false;
        }
        assertEquals(current, snapshot.execution().run().token());
        assertEquals(2, snapshot.execution().run().definition().revision());
        assertEquals(List.of("current-step"), snapshot.execution().steps().stream().map(value -> value.spec().stepId()).toList());
        assertEquals(List.of(new ReceiptProgress("current-step", "scope-current", "periods-frozen", EXPIRY, 0, 1, 0, 0)),
                snapshot.receipts(), "Expired scope references remain internal inputs for the service's authorization filter");
        assertTrue(snapshot.receipts().get(0).expiresAtMillis() < expiredClock.millis());
        assertFalse(fixture.dataSource().auditedSql.isEmpty());
        assertTrue(fixture.dataSource().auditedSql.stream().anyMatch(sql -> sql.contains("campaign_statistics_receipt")));
        assertEquals(before, fixture.databaseState());
    }

    private static RunToken createRun(Fixture fixture) {
        return fixture.runs().createRun(new RunDefinition(OWNER, "session-1", "run-1", "plan-1", 1, "{\"revision\":1}"));
    }

    private static StepSpec step(String id) {
        return new StepSpec(id, "{\"executorVersion\":\"stats/1\"}", List.of(), Set.of(), Set.of());
    }

    private static ChildSpec prepareChild(CampaignRunStore runs, RunToken run, String stepId, String childId) {
        String actionId = "action-" + childId;
        runs.prepareAction(run, new ActionSpec(actionId, stepId, "TOOL", "campaign_stats", "stats/1", "{}"));
        ChildSpec child = new ChildSpec(childId, actionId, ChildMode.ASYNC,
                "request-r" + run.definition().revision() + "-" + childId,
                new WireRequest("POST", "/analytics/query", "{\"privateRequest\":\"wire-body-marker\"}"));
        runs.prepareChild(run, child);
        return child;
    }

    private static void stage(Fixture fixture, RunToken run, String stepId, String childId,
                              String scope, long totalRows, Integer pageRows) {
        CampaignRunStore runs = fixture.runs();
        ChildSpec child = prepareChild(runs, run, stepId, childId);
        String jobId = "job-" + childId;
        DispatchPermit submit = runs.beginDispatch(run, childId);
        try {
            runs.recordWaiting(submit, jobId);
        } finally {
            runs.callbackExited(submit);
        }
        DispatchPermit receipt = runs.beginReconciliation(run, childId);
        try {
            int pageCount = (int) (totalRows / 500 + (totalRows % 500 == 0 ? 0 : 1));
            CampaignStatisticsResultStore results = fixture.results();
            results.initialize(receipt, new ReceiptSpec(jobId, child.wire().hash(),
                    "artifact-r" + run.definition().revision() + "-" + childId, scope, "periods-frozen", totalRows, pageCount, EXPIRY));
            if (pageRows != null) {
                results.append(receipt, new Page(0, pageCount > 1 ? 1 : null, pageRows,
                        "{\"privateSnapshot\":\"snapshot-marker\"}", "{\"privateMetrics\":\"metrics-marker\"}", payload(pageRows)));
            }
            runs.markUnresolved(receipt);
        } finally {
            runs.callbackExited(receipt);
        }
    }

    private static String payload(int rows) {
        StringJoiner items = new StringJoiner(",", "{\"items\":[", "]}");
        for (int i = 0; i < rows; i++) items.add("{\"row\":\"payload-marker-" + i + "\"}");
        return items.toString();
    }

    private static Fixture fixture() {
        DriverManagerDataSource delegate = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_result_progress_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        delegate.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql")).execute(delegate);
        ProgressReadDataSource guarded = new ProgressReadDataSource(delegate);
        return new Fixture(new JdbcTemplate(guarded), new TransactionTemplate(new DataSourceTransactionManager(guarded)), guarded);
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, ProgressReadDataSource dataSource) {
        private CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        private CampaignStepStore steps() { return new JdbcCampaignStepStore(jdbc, transactions, CLOCK); }
        private CampaignStatisticsResultStore results() { return new JdbcCampaignStatisticsResultStore(jdbc, transactions, CLOCK); }
        private CampaignResultProgressReader reader() { return reader(CLOCK); }
        private CampaignResultProgressReader reader(Clock clock) { return new JdbcCampaignResultProgressReader(jdbc, transactions, clock); }
        private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

        private Map<String, List<Map<String, String>>> databaseState() {
            Map<String, List<Map<String, String>>> state = new LinkedHashMap<>();
            Map<String, String> orders = Map.of(
                    "campaign_run_ledger", "run_id,revision", "campaign_action_ledger", "run_id,revision,action_id",
                    "campaign_child_ledger", "run_id,revision,child_id", "campaign_step_ledger", "run_id,revision,ordinal_index",
                    "campaign_artifact", "artifact_id", "campaign_artifact_payload", "artifact_id",
                    "campaign_statistics_receipt", "run_id,revision,child_id", "campaign_statistics_page", "run_id,revision,child_id,page_index");
            orders.forEach((table, order) -> state.put(table, jdbc.query("SELECT * FROM " + table + " ORDER BY " + order,
                    (rs, row) -> {
                        Map<String, String> values = new LinkedHashMap<>();
                        for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                            values.put(rs.getMetaData().getColumnLabel(column), rs.getString(column));
                        }
                        return values;
                    })));
            return state;
        }
    }

    private static final class ProgressReadDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final List<String> auditedSql = new ArrayList<>();
        private boolean auditProgressReads;

        private ProgressReadDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override
        public Connection getConnection() throws SQLException { return guard(delegate.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return guard(delegate.getConnection(username, password));
        }

        private void audit(Object[] args) {
            if (!auditProgressReads || args == null || args.length == 0 || !(args[0] instanceof String sql)) return;
            String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
            assertTrue(normalized.startsWith("select"), "A progress read must not mutate ledgers: " + sql);
            for (String forbidden : List.of("campaign_statistics_page", "payload_json", "wire_body", "snapshot_json", "metrics_json")) {
                assertFalse(normalized.contains(forbidden), "Progress loaded a private payload column or table: " + sql);
            }
            if (normalized.contains("campaign_statistics_receipt") || normalized.contains("campaign_child_ledger")) {
                assertFalse(normalized.matches("(?s)select\\s+(?:[a-z_][a-z0-9_]*\\.)?\\*.*"),
                        "Progress must use an explicit safe projection for receipts and child requests: " + sql);
            }
            auditedSql.add(normalized);
        }

        private Connection guard(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")) audit(args);
                        try {
                            Object result = method.invoke(connection, args);
                            return method.getName().equals("createStatement") ? guard((Statement) result) : result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private Statement guard(Statement statement) {
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        audit(args);
                        try {
                            return method.invoke(statement, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
