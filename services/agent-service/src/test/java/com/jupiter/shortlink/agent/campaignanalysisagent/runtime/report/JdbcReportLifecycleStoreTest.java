package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcReportLifecycleStoreTest {
    private static final Instant BASE = Instant.ofEpochMilli(1_000);
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private JdbcReportLifecycleStore store;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:report_lifecycle_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "sql/migration/V20260920_22__campaign_report_lifecycle.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        clock = new MutableClock(BASE);
        store = new JdbcReportLifecycleStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), clock);
    }

    @Test
    void publishesIdempotentlyAndReadsByRevisionAndMode() {
        var draft = draft(9_000, 2_000);

        var first = store.publish(draft);
        var second = store.publish(draft);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class)).isEqualTo(1);
        assertThat(store.read(new ReportLifecycleStore.Key("report-1", 2), "owner", "capability",
                ReportLifecycleStore.Mode.HISTORY_VIEW)).contains(first);
        assertThat(store.read(new ReportLifecycleStore.Key("report-1", 2), "owner", "capability",
                ReportLifecycleStore.Mode.HISTORY_VIEW).orElseThrow().manifestJson()).isEqualTo("{}");
        assertThat(store.read(new ReportLifecycleStore.Key("report-1", 2), "owner", "capability",
                ReportLifecycleStore.Mode.EXPORT)).contains(first);
        assertThat(store.read(new ReportLifecycleStore.Key("report-1", 1), "owner", "capability",
                ReportLifecycleStore.Mode.HISTORY_VIEW)).isEmpty();
    }

    @Test
    void rejectsConflictingWriteAndUnauthorizedRead() {
        store.publish(draft(9_000, 2_000));
        var conflicting = new ReportLifecycleStore.Draft(new ReportLifecycleStore.Key("report-1", 2), "run-1", 3,
                "owner", "capability", "{}", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                Instant.ofEpochMilli(10_000), Instant.ofEpochMilli(9_000), Instant.ofEpochMilli(2_000), "different");

        assertThatThrownBy(() -> store.publish(conflicting)).hasMessage("REPORT_DRAFT_CONFLICT");
        assertThatThrownBy(() -> store.read(new ReportLifecycleStore.Key("report-1", 2), "other", "capability",
                ReportLifecycleStore.Mode.HISTORY_VIEW)).hasMessage("REPORT_ACCESS_DENIED");
    }

    @Test
    void appliesModeSpecificExpiryAndSupportsReferenceCleanup() {
        var key = new ReportLifecycleStore.Key("report-1", 2);
        store.publish(draft(9_000, 2_000));
        clock.advanceMillis(1_500);
        assertThatThrownBy(() -> store.read(key, "owner", "capability", ReportLifecycleStore.Mode.EXPORT))
                .hasMessage("REPORT_REUSE_EXPIRED");
        assertThat(store.read(key, "owner", "capability", ReportLifecycleStore.Mode.HISTORY_VIEW)).isPresent();

        long version = store.retain(key, "consumer-1", "owner", "capability");
        assertThat(store.release(key, "consumer-1")).isGreaterThan(version);
        long afterRelease = store.release(key, "consumer-1");
        assertThat(afterRelease).isEqualTo(store.release(key, "consumer-1"));
        clock.advanceMillis(7_000);
        assertThat(store.cleanup(key, afterRelease)).isTrue();
        assertThat(store.read(key, "owner", "capability", ReportLifecycleStore.Mode.HISTORY_VIEW)).isEmpty();
    }

    @Test
    void rejectsExpiredRetention() {
        clock.advanceMillis(9_000);
        assertThatThrownBy(() -> store.publish(draft(9_000, 2_000))).hasMessage("REPORT_EVIDENCE_EXPIRED");
    }

    @Test
    void cleanupCannotDeleteBeforeRetentionEnds() {
        var key = new ReportLifecycleStore.Key("report-1", 2);
        var published = store.publish(draft(9_000, 2_000));
        assertThat(store.cleanup(key, published.version())).isFalse();
        clock.advanceMillis(9_000);
        assertThat(store.cleanup(key, published.version())).isTrue();
    }

    private ReportLifecycleStore.Draft draft(long retainedUntil, long reuseExpiresAt) {
        return new ReportLifecycleStore.Draft(new ReportLifecycleStore.Key("report-1", 2), "run-1", 3,
                "owner", "capability", "{}", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                Instant.ofEpochMilli(10_000), Instant.ofEpochMilli(retainedUntil), Instant.ofEpochMilli(reuseExpiresAt), "payload");
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant current) { this.current = current; }
        void advanceMillis(long amount) { current = current.plusMillis(amount); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
        @Override public long millis() { return current.toEpochMilli(); }
    }
}
