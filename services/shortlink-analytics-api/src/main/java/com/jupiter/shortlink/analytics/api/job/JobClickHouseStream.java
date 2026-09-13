package com.jupiter.shortlink.analytics.api.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;

import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded POST streaming avoids query strings and unbounded response/line materialization. */
@Component
public class JobClickHouseStream implements AutoCloseable {
    private final ApiSettings settings;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "analytics-query-deadlines");
                        t.setDaemon(true);
                        return t;
                    });

    public JobClickHouseStream(ApiSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public void query(
            String replica,
            String sql,
            int maxRows,
            long deadline,
            Consumer<Map<String, Object>> consumer) {
        if (!List.of(settings.clickHouseUrls().split(",")).contains(replica)
                || !settings.clickHouseDatabase().matches("[A-Za-z][A-Za-z0-9_]*"))
            throw new QueryFailure("UNAVAILABLE", "Unconfigured ClickHouse destination");
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0)
            throw new QueryFailure("TOO_LARGE", "Query execution deadline exceeded");
        long millis = Math.min(remaining, 300_000);
        InputStream input = null;
        ScheduledFuture<?> close = null;
        try {
            URI uri =
                    URI.create(
                            replica
                                    + "/?database="
                                    + settings.clickHouseDatabase()
                                    + "&max_execution_time="
                                    + Math.max(1, millis / 1000)
                                    + "&max_memory_usage=268435456&max_bytes_to_read=1073741824&read_overflow_mode=throw&max_result_rows="
                                    + (maxRows + 1)
                                    + "&max_result_bytes=67108864&result_overflow_mode=throw");
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofMillis(millis))
                            .header("X-ClickHouse-User", settings.clickHouseUser())
                            .header("X-ClickHouse-Key", settings.clickHousePassword())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            sql + " FORMAT JSONEachRow", StandardCharsets.UTF_8))
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            input = response.body();
            InputStream body = input;
            close =
                    deadlines.schedule(
                            () -> {
                                try {
                                    body.close();
                                } catch (IOException ignored) {
                                }
                            },
                            Math.max(
                                    1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())),
                            TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                String error = new String(input.readNBytes(4096), StandardCharsets.UTF_8);
                if (error.contains("LIMIT_EXCEEDED")
                        || error.contains("TOO_MANY_ROWS")
                        || error.contains("TOO_MANY_BYTES")
                        || error.contains("TIMEOUT_EXCEEDED"))
                    throw new QueryFailure("TOO_LARGE", "ClickHouse execution budget exceeded");
                throw new QueryFailure("UNAVAILABLE", "ClickHouse job query failed");
            }
            long bytes = 0;
            int rows = 0;
            ByteArrayOutputStream line = new ByteArrayOutputStream(2048);
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1; ) {
                bytes += n;
                if (bytes > 64L * 1024 * 1024
                        || System.nanoTime() > deadline
                        || Thread.currentThread().isInterrupted())
                    throw new QueryFailure("TOO_LARGE", "Job result byte/time budget exceeded");
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        if (line.size() > 0) {
                            if (++rows > maxRows)
                                throw new QueryFailure(
                                        "TOO_LARGE", "Job result row budget exceeded");
                            consumer.accept(parse(line.toByteArray()));
                            line.reset();
                        }
                    } else {
                        if (line.size() >= 65536)
                            throw new QueryFailure("TOO_LARGE", "Result row exceeds 64 KiB");
                        line.write(buffer[i]);
                    }
                }
            }
            if (line.size() > 0) {
                if (++rows > maxRows)
                    throw new QueryFailure("TOO_LARGE", "Job result row budget exceeded");
                consumer.accept(parse(line.toByteArray()));
            }
        } catch (QueryFailure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueryFailure("UNAVAILABLE", "Query interrupted");
        } catch (Exception e) {
            throw new QueryFailure("UNAVAILABLE", "ClickHouse stream failed");
        } finally {
            if (close != null) close.cancel(false);
            if (input != null)
                try {
                    input.close();
                } catch (IOException ignored) {
                }
        }
    }

    private Map<String, Object> parse(byte[] bytes) throws IOException {
        return json.readValue(bytes, new TypeReference<Map<String, Object>>() {});
    }

    public String verify(ManifestPlan plan, long deadline) {
        Map<String, Map<String, Object>> expected = new LinkedHashMap<>();
        try {
            for (var w : plan.windows()) {
                Map<String, Object> proof =
                        json.readValue(w.proof(), new TypeReference<Map<String, Object>>() {});
                var old = expected.putIfAbsent(w.build(), proof);
                if (old != null && !old.equals(proof))
                    throw new QueryFailure("NOT_READY", "Conflicting immutable build proofs");
            }
        } catch (QueryFailure e) {
            throw e;
        } catch (Exception e) {
            throw new QueryFailure("NOT_READY", "Malformed immutable build proof");
        }
        for (String replica : plan.replicas()) {
            try {
                List<String> builds = new ArrayList<>(expected.keySet());
                for (int start = 0; start < builds.size(); start += 64) {
                    List<String> batch = builds.subList(start, Math.min(start + 64, builds.size()));
                    Map<String, Map<String, Object>> actual = new HashMap<>();
                    query(
                            replica,
                            "SELECT build_id,count()"
                                + " n,toString(groupBitXor(cityHash64(receipt_id,payload_hash,validation_result)))"
                                + " digest FROM (SELECT"
                                + " build_id,receipt_id,payload_hash,validation_result FROM"
                                + " rebuild_input WHERE build_id IN ("
                                    + batch.stream()
                                            .map(ClickHouseReader::quote)
                                            .collect(java.util.stream.Collectors.joining(","))
                                    + ") GROUP BY"
                                    + " build_id,receipt_id,payload_hash,validation_result) GROUP"
                                    + " BY build_id",
                            64,
                            deadline,
                            r -> actual.put(r.get("build_id").toString(), r));
                    for (String build : batch) {
                        Map<String, Object>
                                a = actual.getOrDefault(build, Map.of("n", 0, "digest", "0")),
                                e = expected.get(build);
                        if (!Objects.toString(e.get("n")).equals(Objects.toString(a.get("n")))
                                || !Objects.toString(e.get("digest"))
                                        .equals(Objects.toString(a.get("digest"))))
                            throw new QueryFailure(
                                    "NOT_READY", "Frozen build unavailable on replica");
                        if (DimensionProof.required(e)) {
                            List<Map<String, Object>> dimensions = new ArrayList<>();
                            query(replica, DimensionProof.sql(build), 1, deadline, dimensions::add);
                            if (dimensions.size() != 1)
                                throw new QueryFailure("NOT_READY", "Missing frozen dimension proof");
                            DimensionProof.verify(e, dimensions.get(0));
                        }
                    }
                }
                return replica;
            } catch (QueryFailure failed) {
                if ("TOO_LARGE".equals(failed.code) || System.nanoTime() > deadline) throw failed;
            }
        }
        throw new QueryFailure("NOT_READY", "No replica currently proves the frozen builds");
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        deadlines.shutdownNow();
    }
}
