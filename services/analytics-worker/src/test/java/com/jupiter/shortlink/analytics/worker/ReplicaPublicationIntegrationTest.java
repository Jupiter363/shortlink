package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Real Keeper/ReplicatedMergeTree visibility fault; Kafka/archival are covered by the storage
 * integration test.
 */
class ReplicaPublicationIntegrationTest {
    @Test
    void pausedReplicaCannotPublishIncompleteBuildAndCatchesUpBeforePublication() throws Exception {
        var settings = settings("http://127.0.0.1:18124,http://127.0.0.1:18125");
        var store = new ClickHouseStore(settings);
        var second = new ClickHouseStore(settings("http://127.0.0.1:18125"));
        String id = UUID.randomUUID().toString();
        long start = Math.floorDiv(System.currentTimeMillis() - 1200000, 300000) * 300000;
        var ledger = mock(ControlLedger.class);
        var cut = new SourceCut(List.of(new SourceCut.Range("it", "replica", "raw", 0, 0, 0)));
        var job = new HashMap<String, Object>();
        job.put("job_id", id);
        job.put("build_id", id);
        job.put("recovery_epoch", id);
        job.put("source_cut", EventJson.write(cut));
        job.put("start_ms", start);
        job.put("end_ms", start + 300000);
        second.execute("SYSTEM STOP FETCHES rebuild_input");
        try {
            var row = new LinkedHashMap<String, Object>();
            row.put("build_id", id);
            row.put("window_start", start);
            row.put("kind", "CLICK");
            row.put("tenant_id", id);
            row.put("link_id", 1L);
            row.put("event_id", EventIdentity.bind(start + 1, id));
            row.put("payload_hash", "a".repeat(64));
            row.put("occurred_at", start + 1);
            row.put("received_at", start + 1);
            row.put("visitor_hash", "v");
            row.put("ip_hash", "i");
            row.put("validation_result", "VALID");
            row.put("receipt_id", id);
            store.insert("rebuild_input", List.of(row));
            var executor = new RebuildExecutor(ledger, mock(ObjectArchive.class), store, settings);
            assertEquals(
                    "REPLICA_NOT_READY",
                    assertThrows(IllegalStateException.class, () -> executor.run(job))
                            .getMessage());
            verify(ledger, never()).publish(anyMap(), anyList());
            second.execute("SYSTEM START FETCHES rebuild_input");
            second.execute("SYSTEM SYNC REPLICA rebuild_input");
            executor.run(job);
            verify(ledger).publish(job, settings.replicas());
            assertNotNull(job.get("coverage_proof"));
        } finally {
            second.execute("SYSTEM START FETCHES rebuild_input");
        }
    }

    private WorkerSettings settings(String urls) {
        return new WorkerSettings(
                "localhost:19092",
                "analytics-replica-it-token-1234567890",
                "analytics-integration-hash-key-12345678901234567890",
                "http://127.0.0.1:19000",
                "shortlink-it",
                "shortlink-it-only",
                "shortlink-analytics-it",
                urls,
                "shortlink_it",
                "shortlink-it-only",
                "shortlink_replicated_it");
    }
}
