package com.jupiter.shortlink.analytics.worker;

import com.jupiter.shortlink.contract.*;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/internal/analytics/v1/worker")
public class WorkerController {
    private final ControlLedger ledger;
    private final WorkerSettings settings;
    private final String commandUrl;
    private final ArchiveRecovery recovery;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public WorkerController(
            ControlLedger l,
            WorkerSettings s,
            ArchiveRecovery r,
            @Value("${analytics.command.url:http://localhost:8003}") String url) {
        ledger = l;
        settings = s;
        recovery = r;
        commandUrl = url;
    }

    @PostMapping("/readiness")
    public Map<String, Object> readiness(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        authenticate(token);
        var e = ledger.epoch();
        return Map.of(
                "ready",
                e.mode().equals("ACTIVE") && e.commandAck(),
                "recoveryEpoch",
                e.epoch(),
                "mode",
                e.mode());
    }

    @PostMapping("/recovery/begin")
    public synchronized Map<String, Object> begin(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        authenticate(token);
        var reply = command("/internal/v1/analytics/recovery/pause", Map.of());
        if (reply.has("data")) reply = reply.get("data");
        long fence = reply.path("gateFence").asLong(0);
        if (fence < 1) throw new IllegalStateException("Missing Command recovery fence");
        String epoch = ledger.beginRecovery(fence);
        return Map.of(
                "recoveryEpoch",
                epoch,
                "gateFence",
                fence,
                "mode",
                "RECOVERING",
                "restartArchiveConsumer",
                true);
    }

    public record ActivateRequest(String recoveryEpoch) {}

    @PostMapping("/recovery/reconcile")
    public Map<String, Object> reconcile(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody ActivateRequest request) {
        authenticate(token);
        return recovery.reconcile(request.recoveryEpoch());
    }

    @GetMapping("/recovery/{epoch}")
    public Map<String, Object> recoveryStatus(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable String epoch) {
        authenticate(token);
        return recovery.status(epoch);
    }

    @PostMapping("/recovery/activate")
    public synchronized Map<String, Object> activate(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody ActivateRequest request) {
        authenticate(token);
        ledger.assertEpoch(request.recoveryEpoch());
        verifySourceCoverage();
        recovery.requireVerified(request.recoveryEpoch());
        ledger.verifyRecoveryPublications(request.recoveryEpoch());
        long fence = ledger.epoch().gateFence();
        if (fence < 1) throw new IllegalStateException("No Command pause fence");
        command(
                "/internal/v1/analytics/recovery/activate",
                Map.of("recoveryEpoch", request.recoveryEpoch(), "gateFence", fence));
        ledger.activate(request.recoveryEpoch());
        return Map.of("ready", true, "recoveryEpoch", request.recoveryEpoch());
    }

    public record RebuildRequest(long startInclusive, long endExclusive) {}

    public record CoverageRequest(long startInclusive) {}

    @PostMapping("/coverage")
    public Map<String, Object> coverage(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody CoverageRequest request) {
        authenticate(token);
        if (request.startInclusive() < 0) throw new IllegalArgumentException("Invalid time range");
        return ledger.coverage(request.startInclusive());
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody RebuildRequest request) {
        authenticate(token);
        if (System.currentTimeMillis() < request.endExclusive() + 480000)
            throw new IllegalArgumentException(
                    "Online admission must close before canonical rebuild");
        String id = ledger.enqueue(request.startInclusive(), request.endExclusive(), ledger.cut());
        return Map.of("jobId", id, "status", "PENDING");
    }

    @GetMapping("/rebuild/{id}")
    public Map<String, Object> job(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable String id) {
        authenticate(token);
        return ledger.job(id);
    }

    private void verifySourceCoverage() {
        Properties properties = sourceAdminProperties(settings.bootstrap());
        try (var admin = AdminClient.create(properties)) {
            String cluster = admin.describeCluster().clusterId().get();
            var topics =
                    admin.describeTopics(List.of(Topics.CLICK_RAW, Topics.GATEWAY_REQUEST))
                            .allTopicNames()
                            .get();
            var cut = ledger.cut();
            int count = 0;
            for (var t : topics.values())
                for (var p : t.partitions()) {
                    count++;
                    var range =
                            cut.ranges().stream()
                                    .filter(
                                            r ->
                                                    r.clusterId().equals(cluster)
                                                            && r.topicId()
                                                                    .equals(t.topicId().toString())
                                                            && r.partition() == p.partition())
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "Missing initialized source"
                                                                    + " partition"));
                    var tp = new TopicPartition(t.name(), p.partition());
                    long earliest =
                            admin.listOffsets(Map.of(tp, OffsetSpec.earliest()))
                                    .all()
                                    .get()
                                    .get(tp)
                                    .offset();
                    long latest =
                            admin.listOffsets(Map.of(tp, OffsetSpec.latest()))
                                    .all()
                                    .get()
                                    .get(tp)
                                    .offset();
                    if (range.end() < earliest || range.end() > latest)
                        throw new IllegalStateException("ARCHIVE_RESTORE_GAP");
                }
            if (cut.ranges().size() != count)
                throw new IllegalStateException("SOURCE_TOPOLOGY_CHANGED_REQUIRES_RECONCILIATION");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Source coverage verification unavailable", e);
        }
    }

    static Properties sourceAdminProperties(String bootstrap) {
        Properties properties = new Properties();
        KafkaSecurity.apply(properties);
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        // Kafka rejects an API deadline below request.timeout.ms at client construction. The
        // default request timeout is 30 seconds, so both budgets must be explicitly bounded.
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);
        properties.put(AdminClientConfig.RETRIES_CONFIG, 1);
        properties.put(AdminClientConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        return properties;
    }

    private com.fasterxml.jackson.databind.JsonNode command(String path, Object body) {
        try {
            var req =
                    HttpRequest.newBuilder(URI.create(commandUrl + path))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .header("X-Internal-Token", settings.internalToken())
                            .POST(HttpRequest.BodyPublishers.ofString(EventJson.write(body)))
                            .build();
            var r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = r.body();
                    var deadline = BoundedLines.watch(input, 10000)) {
                if (r.statusCode() != 200)
                    throw new IllegalStateException("Command recovery acknowledgement unavailable");
                var data =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(bounded(input));
                if (data.has("code") && !Set.of("0", "200").contains(data.get("code").asText()))
                    throw new IllegalStateException("Command recovery request rejected");
                return data;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Command unavailable", e);
        }
    }

    private static byte[] bounded(java.io.InputStream in) throws java.io.IOException {
        byte[] bytes = in.readNBytes(16385);
        if (bytes.length > 16384)
            throw new IllegalStateException("Command response exceeds budget");
        return bytes;
    }

    private void authenticate(String token) {
        if (token == null
                || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        settings.internalToken().getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("Internal authentication required");
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("code", "FORBIDDEN"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        return ResponseEntity.status(503)
                .body(Map.of("code", "NOT_READY", "message", e.getClass().getSimpleName()));
    }
}
