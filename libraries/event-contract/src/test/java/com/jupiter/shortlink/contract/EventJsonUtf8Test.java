package com.jupiter.shortlink.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EventJsonUtf8Test {
    static Stream<String> textCases() {
        return Stream.of("", "ASCII literal \\uD800 \\uD83D\\uDE80", "中文 café e\u0301\u2028\u2029\uFFFD", "\uD83D\uDE80\uD83D\uDE00",
                "\"\\\n\r\t\b\f\u0000\u001f", "\uD800", "\uDC00",
                "a\uD800b\uDC00c\uD800\uD800\uDC00",
                ("a".repeat(2047) + "\uD83D\uDE80").substring(0, 2048),
                "中文\uD83D\uDE80e\u0301\uD800x\uDC00".repeat(4096));
    }

    @ParameterizedTest
    @MethodSource("textCases")
    void matchesLegacyWireForUnicodeEscapesAndMalformedSurrogates(String text) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("value", text);
        fields.put(text, Arrays.asList(null, true, 302, text));
        fields.put("nullable", null);
        assertLegacyBytes(fields);
        assertLegacyBytes(text);
        assertLegacyBytes(click(text));
        assertLegacyBytes(result(text));
    }

    static IntStream bufferOffsets() {
        // Root-string opening quote shifts the pair by one character. Exercise both sides
        // of Jackson's character buffering and the JDK encoder's byte-buffer boundaries.
        return IntStream.of(1998, 1999, 2000, 3998, 3999, 4000, 7998, 7999, 8000,
                8190, 8191, 8192, 16382, 16383, 16384);
    }

    @ParameterizedTest
    @MethodSource("bufferOffsets")
    void surrogatePairsAndReplacementSurviveBufferBoundaries(int offset) {
        assertLegacyBytes("a".repeat(offset) + "\uD83D\uDE80" + "z".repeat(17000));
        assertLegacyBytes("a".repeat(offset) + "\uD800x\uDC00" + "z".repeat(17000));
        assertLegacyBytes("汉".repeat(offset) + "\uD83D\uDE80" + "z".repeat(17000));
        assertLegacyBytes(click("汉".repeat(offset) + "\"\\\n" + "z".repeat(17000)));
        assertLegacyBytes(result("a".repeat(offset) + "\uD83D\uDE80\uD800x\uDC00"));
    }

    @Test
    void nullsAndEventRecordsKeepTheirExistingSchema() {
        assertLegacyBytes(null);
        assertLegacyBytes(new ClickEventV1("evt", 1, 1, "producer", "租户", 2, "g", 1,
                "s.example", "Ab", 1, null, "::1", "agent\uD800", null, "req", null, 1));
        assertLegacyBytes(new GatewayRequestEventV1("decision\uDC00", 1, 1, "producer",
                RequestSource.REDIRECT, DecisionStage.BUSINESS, "GET", 302, "中文\uD83D\uDE80",
                null, null, "s.example", "Ab", null, "req", null));
    }

    @Test
    void snapshotsAreEagerAndReturnedArraysNeverAlias() {
        var value = new LinkedHashMap<String, Object>();
        value.put("text", "before\uD800");
        byte[] expected = EventJson.write(value).getBytes(StandardCharsets.UTF_8);
        byte[] first = EventJson.writeUtf8(value);
        byte[] second = EventJson.writeUtf8(value);
        assertNotSame(first, second);
        first[0] = 0;
        assertArrayEquals(expected, second);
        value.put("text", List.of("after", "中文"));
        assertArrayEquals(expected, second);
        assertLegacyBytes(value);
        var record = click("plain BMP 中文");
        byte[] recordFirst = EventJson.writeUtf8(record);
        byte[] recordSecond = EventJson.writeUtf8(record);
        assertNotSame(recordFirst, recordSecond);
        recordFirst[0] = 0;
        assertArrayEquals(EventJson.write(record).getBytes(StandardCharsets.UTF_8), recordSecond);
    }

    @Test
    void serializationFailureKeepsPublicExceptionContract() {
        var value = new BrokenValue();
        var previous = assertThrows(IllegalArgumentException.class, () -> EventJson.write(value));
        var current = assertThrows(IllegalArgumentException.class, () -> EventJson.writeUtf8(value));
        assertEquals(previous.getMessage(), current.getMessage());
        assertNotNull(current.getCause());
    }

    public static final class BrokenValue {
        public String getValue() { throw new IllegalStateException("fixture failure"); }
    }

    @Test
    void clickSchemaLocksAllComponentNamesAndTypes() {
        assertEquals(List.of("eventId:java.lang.String", "schemaVersion:int", "occurredAt:long",
                "producerInstanceId:java.lang.String", "tenantId:java.lang.String", "linkId:long",
                "gidAtEvent:java.lang.String", "ownershipVersion:long", "domainNorm:java.lang.String",
                "shortUri:java.lang.String", "routeVersion:long", "uvId:java.lang.String",
                "clientIp:java.lang.String", "userAgent:java.lang.String", "referer:java.lang.String",
                "requestId:java.lang.String", "traceId:java.lang.String", "bucketVersion:int"),
                componentSchema(ClickEventV1.class));
    }

    @Test
    void resultSchemaLocksAllComponentNamesAndTypes() {
        assertEquals(List.of("decisionId:java.lang.String", "schemaVersion:int", "occurredAt:long",
                "producerInstanceId:java.lang.String", "source:com.jupiter.shortlink.contract.RequestSource",
                "stage:com.jupiter.shortlink.contract.DecisionStage", "method:java.lang.String", "status:int",
                "reason:java.lang.String", "tenantId:java.lang.String", "linkId:java.lang.Long",
                "domainNorm:java.lang.String", "shortUri:java.lang.String", "policyRevision:java.lang.Long",
                "requestId:java.lang.String", "traceId:java.lang.String"),
                componentSchema(GatewayRequestEventV1.class));
    }

    @Test
    void completeNonSurrogateBmpCorpusMatchesLegacyInBothKnownSchemas() {
        StringBuilder corpus = new StringBuilder(63_488);
        for (int code = 0; code <= 0xFFFF; code++) {
            if (code < 0xD800 || code > 0xDFFF) corpus.append((char) code);
        }
        assertEquals(63_488, corpus.length());
        String text = corpus.toString();
        assertLegacyBytes(click(text));
        assertLegacyBytes(result(text));
    }

    @Test
    void eachClickStringComponentPreservesSurrogatesAndNull() throws Exception {
        checkEveryStringComponent(click("plain"), 12);
    }

    @Test
    void eachResultStringComponentPreservesSurrogatesAndNull() throws Exception {
        checkEveryStringComponent(result("plain"), 9);
    }

    private static List<String> componentSchema(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getName()).toList();
    }

    private static void checkEveryStringComponent(Record base, int expectedStringCount) throws Exception {
        RecordComponent[] components = base.getClass().getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) values[i] = components[i].getAccessor().invoke(base);
        int checked = 0;
        for (int i = 0; i < components.length; i++) {
            if (components[i].getType() != String.class) continue;
            checked++;
            Object previous = values[i];
            for (String text : Arrays.asList(null, "\uD83D\uDE80", "\uD800", "\uDC00", "中文\"\\\n")) {
                values[i] = text;
                assertLegacyBytes(base.getClass().getDeclaredConstructor(types).newInstance(values));
            }
            values[i] = previous;
        }
        assertEquals(expectedStringCount, checked);
    }

    private static ClickEventV1 click(String text) {
        return new ClickEventV1("evt", 1, 1, "producer", "租户", 2, "g", 1,
                "s.example", "Ab", 1, null, "::1", text, null, "req", null, 1);
    }

    private static GatewayRequestEventV1 result(String text) {
        return new GatewayRequestEventV1("decision", 1, 1, "producer", RequestSource.REDIRECT,
                DecisionStage.BUSINESS, "GET", 302, text, null, null, "s.example", "Ab", null, "req", null);
    }

    private static void assertLegacyBytes(Object value) {
        assertArrayEquals(EventJson.write(value).getBytes(StandardCharsets.UTF_8), EventJson.writeUtf8(value));
    }
}
