package com.jupiter.shortlink.command.batch;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.link.LinkCommandService.Creation;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Streaming format is frozen as creation-ndjson-v1. No readLine(), whole-object buffer, or ID
 * allocation.
 */
public final class ImportParser {
    public static final String VERSION = "creation-ndjson-v1";
    private static final int MAX_LINE_BYTES = 16384;
    private final ObjectMapper json;

    public ImportParser(ObjectMapper json) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public record Row(long number, String digest, Creation creation, String error) {}

    public record Manifest(String sha256, long bytes, long rows, long validRows) {}

    public Manifest parse(
            InputStream input,
            long maxBytes,
            long maxRows,
            Consumer<Creation> validate,
            Consumer<Row> output)
            throws IOException {
        MessageDigest total = sha();
        long bytes = 0, rows = 0, valid = 0;
        byte[] block = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream(512);
        MessageDigest lineDigest = sha();
        boolean overlong = false;
        boolean hasLine = false;
        for (int count; (count = input.read(block)) != -1; ) {
            if (count == 0) continue;
            bytes = Math.addExact(bytes, count);
            if (bytes > maxBytes) throw new IOException("IMPORT_BYTE_LIMIT");
            total.update(block, 0, count);
            for (int i = 0; i < count; i++) {
                int b = block[i] & 255;
                if (b == '\n') {
                    if (++rows > maxRows) throw new IOException("IMPORT_ROW_LIMIT");
                    Row row =
                            row(
                                    rows,
                                    line.toByteArray(),
                                    HexFormat.of().formatHex(lineDigest.digest()),
                                    overlong,
                                    validate);
                    if (row.creation() != null) valid++;
                    output.accept(row);
                    line.reset();
                    lineDigest.reset();
                    overlong = false;
                    hasLine = false;
                } else {
                    hasLine = true;
                    lineDigest.update(block[i]);
                    if (line.size() < MAX_LINE_BYTES) line.write(b);
                    else overlong = true;
                }
            }
        }
        if (hasLine) {
            if (++rows > maxRows) throw new IOException("IMPORT_ROW_LIMIT");
            Row row =
                    row(
                            rows,
                            line.toByteArray(),
                            HexFormat.of().formatHex(lineDigest.digest()),
                            overlong,
                            validate);
            if (row.creation() != null) valid++;
            output.accept(row);
        }
        return new Manifest(HexFormat.of().formatHex(total.digest()), bytes, rows, valid);
    }

    private Row row(
            long number,
            byte[] bytes,
            String digest,
            boolean overlong,
            Consumer<Creation> validate) {
        if (overlong) return new Row(number, digest, null, "ROW_BYTE_LIMIT");
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            Creation creation = json.readValue(text, Creation.class);
            validate.accept(creation);
            return new Row(number, digest, creation, null);
        } catch (Exception e) {
            return new Row(number, digest, null, "INVALID_CREATION");
        }
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha().digest(bytes));
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
