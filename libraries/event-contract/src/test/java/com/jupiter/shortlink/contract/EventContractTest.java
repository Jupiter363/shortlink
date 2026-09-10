package com.jupiter.shortlink.contract;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class EventContractTest {
    @Test
    void eventRoundTripKeepsRetryIdentity() {
        var event =
                new ClickEventV1(
                        EventIdentity.bind(1000, "e"),
                        1,
                        1000,
                        "p",
                        "t",
                        42,
                        "g",
                        1,
                        "s.example",
                        "a",
                        1,
                        "u",
                        "127.0.0.1",
                        "ua",
                        "",
                        "r",
                        "tr",
                        1);
        assertEquals(event, EventJson.read(EventJson.write(event), ClickEventV1.class));
        assertEquals(EventKeys.click(event, 16), EventKeys.click(event, 16));
    }

    @Test
    void cutMembershipPrecedesFactDedupAndTracksTopicIncarnation() {
        var cut = new SourceCut(List.of(new SourceCut.Range("c", "topic-id", "raw", 0, 0, 150)));
        assertTrue(
                cut.contains(
                        new RawReceipt(
                                "c", "topic-id", "raw", 0, 100, 1000, "LogAppendTime", "{}")));
        assertFalse(
                cut.contains(
                        new RawReceipt(
                                "c", "topic-id", "raw", 0, 200, 1000, "LogAppendTime", "{}")));
        assertFalse(
                cut.contains(
                        new RawReceipt(
                                "c", "recreated-id", "raw", 0, 100, 1000, "LogAppendTime", "{}")));
    }

    @Test
    void validityDoesNotDependOnReplayClock() {
        var receipt = new RawReceipt("c", "i", "raw", 0, 1, 1000, "LogAppendTime", "{}");
        assertEquals("FUTURE_EVENT", TimeValidation.validate(receipt, 3_601_000, 5000));
        assertFalse(TimeValidation.online(1000, 1_000_000));
        assertEquals("FUTURE_EVENT", TimeValidation.validate(receipt, 3_601_000, 5000));
    }
}
