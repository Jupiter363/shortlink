package com.jupiter.shortlink.command.batch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.link.LinkCommandService.Creation;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class ImportParserTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ImportParser parser = new ImportParser(json);

    private String row(String url) throws Exception {
        return json.writeValueAsString(new Creation("s.example", url, "g", 0, 0, null, "中文"));
    }

    @Test
    void physicalRowsInvalidRowsAndActualManifestAreStable() throws Exception {
        byte[] bytes =
                (row("https://example.org/a")
                                + "\n{\"unknown\":1}\n"
                                + row("https://example.org/b"))
                        .getBytes(StandardCharsets.UTF_8);
        List<ImportParser.Row> rows = new ArrayList<>();
        var manifest =
                parser.parse(
                        new ByteArrayInputStream(bytes),
                        10000,
                        10,
                        c -> {
                            if (c.originUrl() == null) throw new IllegalArgumentException();
                        },
                        rows::add);
        assertEquals(3, manifest.rows());
        assertEquals(2, manifest.validRows());
        assertEquals(bytes.length, manifest.bytes());
        assertEquals(ImportParser.sha256(bytes), manifest.sha256());
        assertEquals(List.of(1L, 2L, 3L), rows.stream().map(ImportParser.Row::number).toList());
        assertEquals("INVALID_CREATION", rows.get(1).error());
        assertEquals("中文", rows.get(2).creation().describe());
    }

    @Test
    void byteAndRowBudgetsAbortBeforeAdmission() throws Exception {
        byte[] bytes =
                (row("https://example.org") + "\n").repeat(3).getBytes(StandardCharsets.UTF_8);
        assertThrows(
                IOException.class,
                () ->
                        parser.parse(
                                new ByteArrayInputStream(bytes),
                                bytes.length - 1,
                                10,
                                c -> {},
                                r -> {}));
        assertThrows(
                IOException.class,
                () -> parser.parse(new ByteArrayInputStream(bytes), 10000, 2, c -> {}, r -> {}));
    }

    @Test
    void overlongAndMalformedUtf8RowsStayBounded() throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes("x".repeat(50000).getBytes(StandardCharsets.UTF_8));
        input.write('\n');
        input.write(0xff);
        input.write('\n');
        input.writeBytes(row("https://example.org").getBytes(StandardCharsets.UTF_8));
        List<ImportParser.Row> rows = new ArrayList<>();
        var manifest =
                parser.parse(
                        new ByteArrayInputStream(input.toByteArray()),
                        100000,
                        10,
                        c -> {},
                        rows::add);
        assertEquals(3, manifest.rows());
        assertEquals(1, manifest.validRows());
        assertEquals("ROW_BYTE_LIMIT", rows.get(0).error());
        assertEquals("INVALID_CREATION", rows.get(1).error());
    }

    @Test
    void fenceFailureStopsStreamingImmediately() throws Exception {
        byte[] bytes =
                (row("https://example.org") + "\n").repeat(10).getBytes(StandardCharsets.UTF_8);
        AtomicInteger emitted = new AtomicInteger();
        assertThrows(
                BatchJobService.StaleLeaseException.class,
                () ->
                        parser.parse(
                                new ByteArrayInputStream(bytes),
                                10000,
                                20,
                                c -> {},
                                r -> {
                                    emitted.incrementAndGet();
                                    throw new BatchJobService.StaleLeaseException();
                                }));
        assertEquals(1, emitted.get());
    }
}
