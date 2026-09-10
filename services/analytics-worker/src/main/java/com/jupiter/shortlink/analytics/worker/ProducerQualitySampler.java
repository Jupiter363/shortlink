package com.jupiter.shortlink.analytics.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.EventEnricher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class ProducerQualitySampler {
    private static final Logger LOG = LoggerFactory.getLogger(ProducerQualitySampler.class);
    private final JdbcTemplate db;
    private final WorkerSettings settings;
    private final List<String> endpoints;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    public ProducerQualitySampler(
            JdbcTemplate d,
            WorkerSettings s,
            @Value("${analytics.producer-quality.urls:}") String urls) {
        db = d;
        settings = s;
        endpoints =
                Arrays.stream(urls.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
        if (endpoints.size() > 256)
            throw new IllegalArgumentException(
                    "Producer roster exceeds the bounded sampler capacity");
    }

    @Scheduled(fixedDelayString = "${analytics.producer-quality.poll-delay:10000}")
    public void sample() {
        for (String endpoint : endpoints)
            try {
                sample(endpoint);
            } catch (Exception e) {
                LOG.warn(
                        "Producer quality evidence unavailable for endpoint {}: {}",
                        EventEnricher.sha256(endpoint).substring(0, 12),
                        e.getClass().getSimpleName());
            }
    }

    public void sample(String endpoint) throws Exception {
        var req =
                HttpRequest.newBuilder(URI.create(endpoint + "/internal/v1/events/quality"))
                        .timeout(Duration.ofSeconds(3))
                        .header("X-Internal-Token", settings.internalToken())
                        .GET()
                        .build();
        var response = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        com.fasterxml.jackson.databind.JsonNode root;
        try (var input = response.body();
                var deadline = BoundedLines.watch(input, 3000)) {
            if (response.statusCode() != 200)
                throw new IllegalStateException("Producer quality unavailable");
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length > 16384)
                throw new IllegalStateException("Producer quality exceeds budget");
            root = json.readTree(bytes);
        }
        if (root.has("data")) root = root.get("data");
        String instance = root.path("producerInstanceId").asText();
        long started = root.path("startedAt").asLong(), observed = root.path("observedAt").asLong();
        if (instance.isBlank()
                || instance.length() > 128
                || started <= 0
                || observed < started
                || Math.abs(System.currentTimeMillis() - observed) > 15000)
            throw new IllegalStateException("Invalid producer quality identity/time");
        for (String lane : List.of("click", "result")) {
            var value = root.path("lanes").path(lane);
            long[] n = new long[5];
            int i = 0;
            for (String field :
                    List.of("attempted", "delivered", "failed", "rejected", "pending")) {
                if (!value.path(field).canConvertToLong())
                    throw new IllegalStateException("Missing producer counter");
                n[i++] = value.path(field).longValue();
            }
            long total = 0;
            for (int k = 1; k < 5; k++) {
                if (n[k] < 0) throw new IllegalStateException("Negative producer counter");
                total = Math.addExact(total, n[k]);
            }
            if (total != n[0])
                throw new IllegalStateException("Producer counters are not yet coherent");
            db.update(
                    "INSERT IGNORE INTO"
                        + " analytics_source_quality(endpoint_id,producer_instance_id,lane,started_at,observed_at,attempted,delivered,failed,rejected,pending)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                    EventEnricher.sha256(endpoint),
                    instance,
                    lane,
                    started,
                    observed,
                    n[0],
                    n[1],
                    n[2],
                    n[3],
                    n[4]);
        }
    }
}
