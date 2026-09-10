package com.jupiter.shortlink.analytics.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class ClickHouseReader {
    private final ApiSettings settings;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public ClickHouseReader(ApiSettings s, ObjectMapper j) {
        settings = s;
        json = j;
    }

    public List<Map<String, Object>> query(String replica, String sql, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            if (!settings.clickHouseDatabase().matches("[A-Za-z][A-Za-z0-9_]*"))
                throw new QueryFailure("UNAVAILABLE", "Invalid database configuration");
            var uri =
                    URI.create(
                            replica
                                    + "/?database="
                                    + settings.clickHouseDatabase()
                                    + "&max_execution_time=15&max_memory_usage=268435456&max_result_rows="
                                    + (limit + 1)
                                    + "&result_overflow_mode=throw");
            var req =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(20))
                            .header("X-ClickHouse-User", settings.clickHouseUser())
                            .header("X-ClickHouse-Key", settings.clickHousePassword())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            sql + " FORMAT JSONEachRow", StandardCharsets.UTF_8))
                            .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (var in = resp.body();
                    var deadline = BoundedLines.watch(in, 20000);
                    var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                if (resp.statusCode() != 200)
                    throw new QueryFailure("UNAVAILABLE", "ClickHouse query failed");
                long chars = 0;
                for (String line; (line = BoundedLines.read(r, 1048576)) != null; ) {
                    chars += line.length();
                    if (chars > 16L * 1024 * 1024)
                        throw new QueryFailure(
                                "TOO_LARGE", "Query exceeds materialized byte budget");
                    if (line.isBlank()) continue;
                    result.add(
                            json.readValue(
                                    line,
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            Map<String, Object>>() {}));
                    if (result.size() > limit)
                        throw new QueryFailure(
                                "TOO_LARGE", "Query exceeds the materialized snapshot budget");
                }
            }
            return result;
        } catch (QueryFailure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueryFailure("UNAVAILABLE", "Query interrupted");
        } catch (Exception e) {
            throw new QueryFailure("UNAVAILABLE", "ClickHouse unavailable");
        }
    }

    public static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
