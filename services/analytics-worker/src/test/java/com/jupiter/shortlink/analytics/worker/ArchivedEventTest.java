package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;

import org.junit.jupiter.api.Test;

class ArchivedEventTest {
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
