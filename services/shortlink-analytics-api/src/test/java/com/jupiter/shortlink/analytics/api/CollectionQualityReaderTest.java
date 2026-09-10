package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.EventEnricher;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

class CollectionQualityReaderTest {
    @Test
    void actualCountersDistinguishHealthyLossAndRestart() {
        var db =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:quality;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute(
                "CREATE TABLE analytics_source_quality(endpoint_id VARCHAR,producer_instance_id"
                        + " VARCHAR,lane VARCHAR,started_at BIGINT,observed_at BIGINT,attempted"
                        + " BIGINT,delivered BIGINT,failed BIGINT,rejected BIGINT,pending BIGINT)");
        String endpoint = "http://producer", id = EventEnricher.sha256(endpoint);
        var reader = new CollectionQualityReader(db, endpoint);
        assertEquals("UNKNOWN", reader.read(1000, 2000, 2100).get("status"));
        for (String lane : List.of("click", "result"))
            db.update(
                    "INSERT INTO analytics_source_quality VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id,
                    "instance-1",
                    lane,
                    100,
                    2100,
                    10,
                    10,
                    0,
                    0,
                    0);
        assertEquals("NORMAL", reader.read(1000, 2000, 2200).get("status"));
        db.update("UPDATE analytics_source_quality SET failed=1,delivered=9 WHERE lane='click'");
        assertEquals("DEGRADED", reader.read(1000, 2000, 2200).get("status"));
        db.update(
                "UPDATE analytics_source_quality SET"
                    + " producer_instance_id='restarted',started_at=1500,failed=0,delivered=10");
        assertEquals("UNKNOWN", reader.read(1000, 2000, 2200).get("status"));
    }
}
