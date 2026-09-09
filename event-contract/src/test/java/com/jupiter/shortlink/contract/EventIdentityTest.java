package com.jupiter.shortlink.contract;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class EventIdentityTest {
    @Test
    void exactTimestampBindingRejectsLegacyAndNonCanonicalIdentity() {
        String suffix = UUID.randomUUID().toString(), id = EventIdentity.bind(123456789, suffix);
        assertTrue(EventIdentity.valid(id, 123456789));
        assertTrue(
                EventIdentity.valid(
                        EventIdentity.bind(123456789, "instance:" + suffix), 123456789));
        assertFalse(EventIdentity.valid(id, 123456790));
        assertFalse(EventIdentity.valid(id, 123756789));
        assertFalse(EventIdentity.valid(suffix, 123456789));
        assertFalse(EventIdentity.valid("v1:0123456789:" + suffix, 123456789));
        assertFalse(EventIdentity.valid("v1:123456789:", 123456789));
        assertTrue(EventIdentity.valid(EventIdentity.bind(1, "x".repeat(256)), 1));
        assertThrows(IllegalArgumentException.class, () -> EventIdentity.bind(0, suffix));
        assertThrows(IllegalArgumentException.class, () -> EventIdentity.bind(1, "x".repeat(257)));
    }

    @Test
    void changedWindowIsRejectedForClickAndRequestBeforeAnyStatefulDeduplication() {
        long original = 600001, changed = 900001;
        String id = EventIdentity.bind(original, "controlled-issuance");
        var interpreter = new EventEnricher("abcdefghijklmnopqrstuvwxyz0123456789", 5000);
        var good = click(id, original);
        var forged = click(id, changed);
        var receipt = raw(Topics.CLICK_RAW, 1, EventJson.write(good));
        assertTrue(interpreter.enrich(receipt).valid());
        var bad = interpreter.enrich(raw(Topics.CLICK_RAW, 2, EventJson.write(forged)));
        assertEquals("INVALID_EVENT_IDENTITY", bad.validationResult());
        assertEquals("broker-time-v2", bad.validationVersion());
        assertEquals(bad, interpreter.enrich(raw(Topics.CLICK_RAW, 2, EventJson.write(forged))));
        var request =
                new GatewayRequestEventV1(
                        id,
                        1,
                        changed,
                        "p",
                        RequestSource.REDIRECT,
                        DecisionStage.BUSINESS,
                        "GET",
                        403,
                        "RISK_DENIED",
                        "tenant",
                        1L,
                        "short.test",
                        "a",
                        1L,
                        "r",
                        "t");
        assertEquals(
                "INVALID_EVENT_IDENTITY",
                interpreter
                        .enrich(raw(Topics.GATEWAY_REQUEST, 3, EventJson.write(request)))
                        .validationResult());
    }

    @Test
    void sameWindowBehaviorChangesStillProduceDetectableDistinctPayloadHashes() {
        var interpreter = new EventEnricher("abcdefghijklmnopqrstuvwxyz0123456789", 5000);
        String id = EventIdentity.bind(600001, "controlled-issuance");
        var a = click(id, 600001);
        var b =
                new ClickEventV1(
                        id,
                        1,
                        600001,
                        "p",
                        "tenant",
                        2,
                        "g",
                        1,
                        "short.test",
                        "b",
                        1,
                        "visitor",
                        "127.0.0.1",
                        null,
                        null,
                        "r",
                        "t",
                        1);
        var first = interpreter.enrich(raw(Topics.CLICK_RAW, 1, EventJson.write(a)));
        var second = interpreter.enrich(raw(Topics.CLICK_RAW, 2, EventJson.write(b)));
        assertTrue(first.valid());
        assertTrue(second.valid());
        assertNotEquals(first.payloadHash(), second.payloadHash());
    }

    private ClickEventV1 click(String id, long time) {
        return new ClickEventV1(
                id,
                1,
                time,
                "p",
                "tenant",
                1,
                "g",
                1,
                "short.test",
                "a",
                1,
                "visitor",
                "127.0.0.1",
                null,
                null,
                "r",
                "t",
                1);
    }

    private RawReceipt raw(String topic, long offset, String payload) {
        return new RawReceipt("c", "t", topic, 0, offset, 1000000, "LogAppendTime", payload);
    }
}
