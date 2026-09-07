package com.jupiter.shortlink.analytics.flink;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;

import org.junit.jupiter.api.Test;

class InterpretationTest {
    @Test
    void canonicalAndOnlineUseOneInterpreterAndStableIdentity() {
        var e =
                new ClickEventV1(
                        EventIdentity.bind(1000, "one"),
                        1,
                        1000,
                        "p",
                        "tenant",
                        5,
                        "g",
                        1,
                        "short.test",
                        "A",
                        1,
                        "cookie",
                        "127.0.0.1",
                        "Mozilla Chrome/10",
                        "",
                        "r",
                        "t",
                        1);
        var raw =
                new RawReceipt(
                        "cluster",
                        "topic",
                        Topics.CLICK_RAW,
                        0,
                        0,
                        2000,
                        "LogAppendTime",
                        EventJson.write(e));
        var parser = new EventEnricher("01234567890123456789012345678901", 5000);
        assertEquals(parser.enrich(raw), parser.enrich(raw));
        assertTrue(parser.enrich(raw).valid());
        assertFalse(EventJson.write(parser.enrich(raw)).contains("127.0.0.1"));
    }

    @Test
    void sketchesMergeSetsNotCardinalities() {
        HllSketch a = new HllSketch(), b = new HllSketch();
        for (int n = 0; n < 1000; n++) {
            String h = EventEnricher.sha256("visitor" + n);
            a.add(h);
            b.add(h);
        }
        double before = a.estimate();
        a.merge(b);
        assertEquals(before, a.estimate());
        assertTrue(Math.abs(before - 1000) < 120);
    }
}
