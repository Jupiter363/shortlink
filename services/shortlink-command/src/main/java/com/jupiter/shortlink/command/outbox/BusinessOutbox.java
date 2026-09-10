package com.jupiter.shortlink.command.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

@Component
public class BusinessOutbox {
    public record Entry(String eventId, String topic, String key, Object event) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public BusinessOutbox(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void append(String eventId, String topic, String key, Object event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox requires the business transaction");
        try {
            String payload = json.writeValueAsString(event);
            if (payload.length() > 65536)
                throw new IllegalArgumentException("Outbox payload too large");
            jdbc.update(
                    "INSERT INTO"
                        + " t_outbox(event_id,topic,event_key,payload,next_attempt_at,created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                    eventId,
                    topic,
                    key,
                    payload,
                    clock.millis(),
                    clock.millis());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid event", e);
        }
    }

    public void appendMany(java.util.List<Entry> entries) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox requires the business transaction");
        if (entries.isEmpty()) return;
        if (entries.size() > 2000)
            throw new IllegalArgumentException("Outbox batch exceeds budget");
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        try {
            for (Entry e : entries) {
                String payload = json.writeValueAsString(e.event());
                if (payload.length() > 65536)
                    throw new IllegalArgumentException("Outbox payload too large");
                rows.add(
                        new Object[] {
                            e.eventId(), e.topic(), e.key(), payload, clock.millis(), clock.millis()
                        });
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid event", e);
        }
        jdbc.batchUpdate(
                "INSERT INTO t_outbox(event_id,topic,event_key,payload,next_attempt_at,created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                rows);
    }
}
