package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

class ArchivedEventTest {
    @Test
    void preGeoArchiveStillReadsWithoutChangingFrozenIdentityOrValidation() throws Exception {
        String key = "abcdefghijklmnopqrstuvwxyz0123456789";
        var event = new ClickEventV1(EventIdentity.bind(1000, "legacy"), 1, 1000, "p", "t", 1,
                "g", 1, "example.test", "x", 1, "u", "1.2.3.4", "Chrome/10", "", "r", "t", 1);
        var raw = new RawReceipt("c", "t", Topics.CLICK_RAW, 0, 42, 2000, "LogAppendTime",
                EventJson.write(event));
        var captured = ArchivedEvent.capture(raw, new EventEnricher(key, 5000), key);
        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(EventJson.write(captured));
        ObjectNode interpretation = (ObjectNode) json.get("interpretation");
        interpretation.remove(java.util.List.of("province", "city", "network", "geoStatus", "geoVersion"));
        interpretation.put("parserVersion", "builtin-ua-v1");
        var restored = EventJson.read(json.toString(), ArchivedEvent.class);
        var verified = restored.verified();
        assertEquals("detail-v1", verified.detailDatasetVersion());
        assertEquals("builtin-ua-v1", verified.parserVersion());
        assertEquals(captured.interpretation().payloadHash(), verified.payloadHash());
        assertEquals(captured.raw(), restored.raw());
        assertEquals("VALID", verified.validationResult());
        assertEquals("UNKNOWN", verified.province());
        assertEquals("UNKNOWN", verified.city());
        assertEquals("UNKNOWN", verified.network());
        assertEquals("UNKNOWN", verified.geoStatus());
        assertEquals("", verified.geoVersion());
    }

    @Test
    void replayUsesCapturedValidationAndHashes() {
        String key = "abcdefghijklmnopqrstuvwxyz0123456789";
        var parser = new EventEnricher(key, 5000);
        var event =
                new ClickEventV1(
                        EventIdentity.bind(20000, "future"),
                        1,
                        20000,
                        "p",
                        "t",
                        1,
                        "g",
                        1,
                        "example.test",
                        "x",
                        1,
                        "u",
                        "127.0.0.1",
                        null,
                        null,
                        "r",
                        "t",
                        1);
        var receipt =
                new RawReceipt(
                        "c",
                        "t",
                        Topics.CLICK_RAW,
                        0,
                        42,
                        10000,
                        "LogAppendTime",
                        EventJson.write(event));
        var archive = ArchivedEvent.capture(receipt, parser, key);
        var restored = EventJson.read(EventJson.write(archive), ArchivedEvent.class);
        assertEquals("FUTURE_EVENT", restored.verified().validationResult());
        assertEquals(archive.interpretation(), restored.verified());
        assertEquals(10000, restored.raw().receivedAt());
    }
}
