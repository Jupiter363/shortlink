package com.jupiter.shortlink.analytics.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.EventJson;

import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

@Component
public class ClickHouseStore {
    private final WorkerSettings settings;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public ClickHouseStore(WorkerSettings settings) {
        this.settings = settings;
    }

    public void insert(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        if (!Set.of("event_receipts", "window_results", "rebuild_input").contains(table))
            throw new IllegalArgumentException("Unknown table");
        StringBuilder body = new StringBuilder();
        rows.forEach(row -> body.append(EventJson.write(row)).append('\n'));
        request(
                settings.replicas().get(0),
                "INSERT INTO " + table + " FORMAT JSONEachRow",
                body.toString(),
                s -> {});
    }

    public void query(String sql, Consumer<Map<String, Object>> consumer) {
        query(settings.replicas().get(0), sql, consumer);
    }

    public void query(String replica, String sql, Consumer<Map<String, Object>> consumer) {
        request(
                replica,
                sql + " FORMAT JSONEachRow",
                "",
                line -> {
                    try {
                        consumer.accept(json.readValue(line, Map.class));
                    } catch (IOException e) {
                        throw new IllegalStateException("Invalid ClickHouse response", e);
                    }
                });
    }

    public void execute(String sql) {
        request(settings.replicas().get(0), sql, "", s -> {});
    }

    private void request(String replica, String sql, String body, Consumer<String> each) {
        try {
            URI uri =
                    URI.create(
                            replica
                                    + "/?database="
                                    + settings.clickhouseDatabase()
                                    + "&max_execution_time=60&max_memory_usage=536870912&query="
                                    + URLEncoder.encode(sql, StandardCharsets.UTF_8));
            var req =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(65))
                            .header("X-ClickHouse-User", settings.clickhouseUser())
                            .header("X-ClickHouse-Key", settings.clickhousePassword())
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var response = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (var in = response.body();
                    var deadline = BoundedLines.watch(in, 65000);
                    var reader =
                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                if (response.statusCode() != 200)
                    throw new IllegalStateException("ClickHouse HTTP " + response.statusCode());
                long chars = 0;
                for (String line; (line = BoundedLines.read(reader, 1048576)) != null; ) {
                    chars += line.length();
                    if (chars > 64L * 1024 * 1024)
                        throw new IllegalStateException("ClickHouse result exceeds budget");
                    if (!line.isBlank()) each.accept(line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("ClickHouse unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ClickHouse interrupted", e);
        }
    }

    public static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
