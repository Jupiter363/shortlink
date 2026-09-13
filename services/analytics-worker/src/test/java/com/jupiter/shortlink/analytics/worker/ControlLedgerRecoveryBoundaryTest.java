package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.EventJson;
import com.jupiter.shortlink.contract.SourceCut;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class ControlLedgerRecoveryBoundaryTest {
    private static final String EPOCH = "recovery-under-live-traffic";
    // At 12:13, the 12:00-12:05 window has just closed its eight-minute admission period.
    private static final long STARTED = Instant.parse("2026-09-13T12:13:00Z").toEpochMilli();
    private static final long CLOSED_END = Instant.parse("2026-09-13T12:05:00Z").toEpochMilli();
    private JdbcTemplate db;
    private ControlLedger ledger;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE analytics_epoch(singleton INT PRIMARY KEY, recovery_epoch"
                + " VARCHAR(64),mode VARCHAR(24),command_ack BOOLEAN,gate_fence BIGINT)");
        db.execute("CREATE TABLE analytics_recovery_run(recovery_epoch VARCHAR(64) PRIMARY KEY,"
                + "status VARCHAR(24),updated_at TIMESTAMP(3))");
        db.execute("CREATE TABLE analytics_manifest(window_start BIGINT PRIMARY KEY,"
                + "recovery_epoch VARCHAR(64),source_cut VARCHAR(2048))");
        db.execute("CREATE TABLE analytics_rebuild_job(job_id VARCHAR(64) PRIMARY KEY,"
                + "recovery_epoch VARCHAR(64),start_ms BIGINT,status VARCHAR(24),"
                + "end_ms BIGINT,source_cut VARCHAR(2048))");
        db.execute("CREATE TABLE analytics_repair_request(window_start BIGINT PRIMARY KEY,"
                + "generation BIGINT,required_cut VARCHAR(1024),requested_at TIMESTAMP(3))");
        db.execute("CREATE TABLE analytics_archive_segment(cluster_id VARCHAR(64),"
                + "topic_id VARCHAR(64),partition_id INT,start_offset BIGINT,end_offset BIGINT)");
        db.update("INSERT INTO analytics_epoch VALUES(1,?,'RECOVERING',FALSE,23)", EPOCH);
        db.update("INSERT INTO analytics_recovery_run VALUES(?,'VERIFIED',?)", EPOCH,
                new Timestamp(STARTED));
        var archive = mock(ObjectArchive.class);
        when(archive.activeEpoch()).thenReturn(EPOCH);
        ledger = new ControlLedger(db, new DataSourceTransactionManager(ds), archive);
    }

    @Test
    void continuousNewWindowsDoNotPreventActivationOrConsumeDurableRepairs() {
        for (int i = 0; i < 20; i++) {
            long window = CLOSED_END + i * 300000L;
            repair(window, STARTED + i * 300000L);
            job("live-" + i, "PENDING", window, window + 300000, cut(0, 100));
            assertDoesNotThrow(() -> ledger.verifyRecoveryPublications(EPOCH));
        }
        assertEquals(20L, db.queryForObject("SELECT count(*) FROM analytics_repair_request", Long.class));
        assertEquals(20L, db.queryForObject("SELECT count(*) FROM analytics_rebuild_job", Long.class));
        assertEquals("RECOVERING", ledger.epoch().mode());
        assertFalse(ledger.epoch().commandAck());
        assertEquals(23L, ledger.epoch().gateFence());
    }

    @Test
    void lateRepairForAlreadyClosedWindowStillBlocksRegardlessOfArrivalTime() {
        repair(CLOSED_END - 300000, STARTED + 86400000);
        assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
        assertEquals("required-archive-cut", db.queryForObject(
                "SELECT required_cut FROM analytics_repair_request", String.class));
    }

    @Test
    void pendingRunningAndFailedClosedWindowBuildsEachBlock() {
        for (String status : new String[] {"PENDING", "RUNNING", "FAILED"}) {
            job(status, status, CLOSED_END - 300000, CLOSED_END, cut(0, 100));
            assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
            db.update("DELETE FROM analytics_rebuild_job WHERE job_id=?", status);
        }
        job("complete", "PUBLISHED", CLOSED_END - 300000, CLOSED_END, cut(0, 100));
        assertDoesNotThrow(() -> ledger.verifyRecoveryPublications(EPOCH));
    }

    @Test
    void oldEpochManifestStillBlocksEvenOutsideClosedWindowBoundary() {
        db.update("INSERT INTO analytics_manifest VALUES(?,'old-epoch',?)", CLOSED_END + 300000,
                EventJson.write(cut(0, 100)));
        assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
        db.update("UPDATE analytics_manifest SET recovery_epoch=?", EPOCH);
        assertDoesNotThrow(() -> ledger.verifyRecoveryPublications(EPOCH));
    }

    @Test
    void rawCatalogAndEpochProofRemainMandatory() {
        db.update("UPDATE analytics_recovery_run SET status='VERIFYING'");
        assertThrows(RuntimeException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
        db.update("UPDATE analytics_recovery_run SET status='VERIFIED'");
        assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications("stale"));
    }

    @Test
    void failedAttemptCoveredByEqualOrBroaderPublicationNoLongerBlocksAndRemainsRecorded() {
        long window = CLOSED_END - 300000;
        job("failed-original", "FAILED", window, CLOSED_END, cut(10, 100));
        manifest(window, cut(10, 100));
        assertDoesNotThrow(() -> ledger.verifyRecoveryPublications(EPOCH));
        db.update("UPDATE analytics_manifest SET source_cut=?", EventJson.write(cut(0, 200)));
        assertDoesNotThrow(() -> ledger.verifyRecoveryPublications(EPOCH));
        assertEquals("FAILED", db.queryForObject(
                "SELECT status FROM analytics_rebuild_job WHERE job_id='failed-original'", String.class));
        assertEquals(EventJson.write(cut(10, 100)), db.queryForObject(
                "SELECT source_cut FROM analytics_rebuild_job WHERE job_id='failed-original'", String.class));
    }

    @Test
    void successfulJobWithoutManifestDoesNotSatisfyFailedAttempt() {
        long window = CLOSED_END - 300000;
        job("failed-original", "FAILED", window, CLOSED_END, cut(0, 100));
        job("successful-retry", "PUBLISHED", window, CLOSED_END, cut(0, 200));
        assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
    }

    @Test
    void everyWindowOfFailedAttemptNeedsCoveringPublication() {
        long start = CLOSED_END - 600000;
        job("failed-range", "FAILED", start, CLOSED_END, cut(0, 100));
        manifest(start, cut(0, 200));
        assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
        manifest(start + 300000, cut(0, 50));
        assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
        db.update("UPDATE analytics_manifest SET source_cut=? WHERE window_start=?",
                EventJson.write(cut(0, 200)), start + 300000);
        assertDoesNotThrow(() -> ledger.verifyRecoveryPublications(EPOCH));
    }

    @Test
    void narrowerOffsetsOrMissingSourcePartitionCannotSatisfyFailedAttempt() {
        long window = CLOSED_END - 300000;
        var required = new SourceCut(List.of(
                new SourceCut.Range("cluster", "topic-id", "click", 0, 10, 100),
                new SourceCut.Range("cluster", "topic-id", "click", 1, 10, 100)));
        job("failed-partitions", "FAILED", window, CLOSED_END, required);
        manifest(window, cut(0, 200));
        assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
        for (var incomplete : List.of(
                new SourceCut.Range("cluster", "topic-id", "click", 1, 11, 100),
                new SourceCut.Range("cluster", "topic-id", "click", 1, 10, 99),
                new SourceCut.Range("other-cluster", "topic-id", "click", 1, 0, 200),
                new SourceCut.Range("cluster", "recreated-topic-id", "click", 1, 0, 200))) {
            db.update("UPDATE analytics_manifest SET source_cut=?", EventJson.write(new SourceCut(
                    List.of(required.ranges().get(0), incomplete))));
            assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
        }
        db.update("UPDATE analytics_manifest SET source_cut=?", EventJson.write(required));
        assertDoesNotThrow(() -> ledger.verifyRecoveryPublications(EPOCH));
    }

    @Test
    void failedAttemptAuditPagesCannotSkipUncoveredLaterAttempt() {
        long window = CLOSED_END - 300000;
        manifest(window, cut(0, 100));
        for (int i = 0; i < 101; i++)
            job(String.format("failed-%03d", i), "FAILED", window, CLOSED_END, cut(0, 100));
        job("failed-999", "FAILED", window, CLOSED_END, cut(0, 200));
        assertThrows(IllegalStateException.class, () -> ledger.verifyRecoveryPublications(EPOCH));
        db.update("UPDATE analytics_manifest SET source_cut=?", EventJson.write(cut(0, 200)));
        assertDoesNotThrow(() -> ledger.verifyRecoveryPublications(EPOCH));
        assertEquals(102L, db.queryForObject(
                "SELECT count(*) FROM analytics_rebuild_job WHERE status='FAILED'", Long.class));
    }

    @Test
    void nonemptyArchiveCutRequiresAnActualMatchingSegment() {
        var range = cut(0, 10).ranges().get(0);
        assertArchiveGap(range);
        db.update("INSERT INTO analytics_archive_segment VALUES('other-cluster','topic-id',0,0,10)");
        db.update("INSERT INTO analytics_archive_segment VALUES('cluster','old-topic-id',0,0,10)");
        db.update("INSERT INTO analytics_archive_segment VALUES('cluster','topic-id',1,0,10)");
        assertArchiveGap(range);
        assertEquals(3L, db.queryForObject("SELECT count(*) FROM analytics_archive_segment", Long.class));
    }

    @Test
    void emptyArchiveCutRemainsValidWithoutCatalogSegments() {
        assertDoesNotThrow(() -> ledger.verifyArchiveCut(cut(0, 0).ranges().get(0)));
        assertDoesNotThrow(() -> ledger.verifyArchiveCut(cut(10, 10).ranges().get(0)));
    }

    @Test
    void archiveCutRequiresBothEdgesAndAnUnbrokenInterior() {
        var range = cut(0, 10).ranges().get(0);
        segment(0, 4);
        assertArchiveGap(range);
        segment(6, 10);
        assertArchiveGap(range);
        segment(4, 6);
        assertDoesNotThrow(() -> ledger.verifyArchiveCut(range));
        db.update("DELETE FROM analytics_archive_segment WHERE start_offset=0");
        assertArchiveGap(range);
    }

    @Test
    void segmentsMayExtendPastEitherSideOfTheRequiredCut() {
        segment(0, 5);
        segment(5, 20);
        assertDoesNotThrow(() -> ledger.verifyArchiveCut(cut(2, 10).ranges().get(0)));
    }

    private void assertArchiveGap(SourceCut.Range range) {
        var failure = assertThrows(IllegalStateException.class, () -> ledger.verifyArchiveCut(range));
        assertEquals("ARCHIVE_COVERAGE_GAP", failure.getMessage());
    }

    private void segment(long start, long end) {
        db.update("INSERT INTO analytics_archive_segment VALUES('cluster','topic-id',0,?,?)", start, end);
    }

    private SourceCut cut(long start, long end) {
        return new SourceCut(List.of(new SourceCut.Range("cluster", "topic-id", "click", 0, start, end)));
    }

    private void job(String id, String status, long start, long end, SourceCut cut) {
        db.update("INSERT INTO analytics_rebuild_job VALUES(?,?,?,?,?,?)", id, EPOCH, start, status,
                end, EventJson.write(cut));
    }

    private void manifest(long window, SourceCut cut) {
        db.update("INSERT INTO analytics_manifest VALUES(?,?,?)", window, EPOCH, EventJson.write(cut));
    }

    private void repair(long window, long requestedAt) {
        db.update("INSERT INTO analytics_repair_request VALUES(?,1,'required-archive-cut',?)",
                window, new Timestamp(requestedAt));
    }
}
