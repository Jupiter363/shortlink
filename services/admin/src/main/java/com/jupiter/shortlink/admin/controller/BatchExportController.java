package com.jupiter.shortlink.admin.controller;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.remote.BatchCommandRemoteService;
import com.jupiter.shortlink.admin.remote.dto.resp.CommittedCreateExportRow;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Synchronous servlet streaming uses the bounded management request pool, not MVC's default async
 * executor.
 */
@RestController
public class BatchExportController {
    private static final Set<String> TERMINAL =
            Set.of("SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "CANCELLED");
    private final BatchCommandRemoteService jobs;
    private final ObjectMapper json;
    private final String baseUrl, token;
    private final Semaphore permits = new Semaphore(8);

    public BatchExportController(
            BatchCommandRemoteService jobs,
            ObjectMapper json,
            @Value("${shortlink.command.base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${shortlink.internal-token}") String token) {
        if (token == null || token.length() < 32)
            throw new IllegalArgumentException("Internal token required");
        this.jobs = jobs;
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    @GetMapping("/api/short-link/admin/v1/create/batch/export")
    public void committed(@RequestParam String requestId, HttpServletResponse response)
            throws IOException {
        var identity = identity();
        if (requestId == null || requestId.isBlank() || requestId.length() > 96)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid requestId");
        acquire();
        try {
            List<CommittedCreateExportRow> rows = readCommitted(requestId, identity);
            headers(
                    response,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "created-links.xlsx");
            List<List<Object>> cells = new ArrayList<>(rows.size());
            for (var row : rows)
                cells.add(
                        List.of(
                                Long.toString(row.linkId()),
                                safe(row.fullShortUrl()),
                                safe(row.originUrl()),
                                safe(row.gid())));
            EasyExcel.write(response.getOutputStream())
                    .autoCloseStream(false)
                    .head(
                            List.of(
                                    List.of("linkId"),
                                    List.of("fullShortUrl"),
                                    List.of("originUrl"),
                                    List.of("gid")))
                    .sheet("Committed results")
                    .doWrite(cells);
        } finally {
            permits.release();
        }
    }

    @GetMapping("/api/short-link/admin/v1/batches/{job}/export")
    public void job(@PathVariable String job, HttpServletResponse response) throws IOException {
        var identity = identity();
        acquire();
        try {
            var status = bound(identity, () -> jobs.status(job));
            requireTerminal(status);
            headers(response, "text/csv;charset=UTF-8", "batch-results.csv");
            stream(job, status, identity, response.getOutputStream());
        } finally {
            permits.release();
        }
    }

    void stream(
            String job,
            BatchCommandRemoteService.Status frozen,
            UserInfoDTO identity,
            OutputStream output)
            throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5),
                after = 0,
                emitted = 0;
        Writer writer =
                new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 8192);
        writer.write('\ufeff');
        writer.write("row,state,linkId,fullShortUrl,originUrl,error\r\n");
        while (true) {
            if (System.nanoTime() > deadline)
                throw new IOException(
                        "Export time budget exceeded; retry from the immutable terminal job");
            var current = bound(identity, () -> jobs.status(job));
            if (!frozen.equals(current))
                throw new IOException("Terminal job changed during export");
            long cursor = after;
            var page = bound(identity, () -> jobs.results(job, cursor, 500));
            if (page == null || page.size() > 500) throw new IOException("Invalid export page");
            if (page.isEmpty()) break;
            for (var row : page) {
                if (row.row() <= after || ++emitted > 5_000_000)
                    throw new IOException("Invalid result cursor or row budget exceeded");
                after = row.row();
                Map<String, Object> result = row.result() == null ? Map.of() : row.result();
                writer.write(
                        csv(
                                Long.toString(row.row()),
                                row.state(),
                                row.linkId() == null ? "" : row.linkId().toString(),
                                Objects.toString(result.get("fullShortUrl"), ""),
                                Objects.toString(result.get("originUrl"), ""),
                                row.error()));
            }
            writer.flush();
        }
        writer.flush();
    }

    private List<CommittedCreateExportRow> readCommitted(String requestId, UserInfoDTO identity)
            throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection)
                        URI.create(
                                        baseUrl
                                                + "/internal/command/creates/result?requestId="
                                                + URLEncoder.encode(
                                                        requestId, StandardCharsets.UTF_8))
                                .toURL()
                                .openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("X-Internal-Token", token);
            connection.setRequestProperty("x-shortlink-tenant-id", identity.getUserId());
            connection.setRequestProperty("x-shortlink-username", identity.getUsername());
            connection.setRequestProperty(
                    "x-shortlink-auth-version", identity.getAuthVersion().toString());
            int status = connection.getResponseCode();
            if (status != 200)
                throw new ResponseStatusException(
                        status == 403
                                ? HttpStatus.FORBIDDEN
                                : status == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY,
                        "Committed result unavailable");
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024)
                    throw new IOException("Export response exceeds budget");
                List<CommittedCreateExportRow> rows =
                        json.readValue(
                                bytes, new TypeReference<List<CommittedCreateExportRow>>() {});
                if (rows == null || rows.isEmpty() || rows.size() > 500)
                    throw new IOException("Invalid export row count");
                return rows;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void requireTerminal(BatchCommandRemoteService.Status status) {
        if (status == null || !TERMINAL.contains(status.state()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Export requires a terminal job");
    }

    private void acquire() {
        if (!permits.tryAcquire())
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Export capacity exhausted");
    }

    private static void headers(HttpServletResponse r, String type, String filename) {
        r.setContentType(type);
        r.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        r.setHeader("Cache-Control", "no-store, private");
        r.setHeader("X-Content-Type-Options", "nosniff");
    }

    private static UserInfoDTO identity() {
        if (UserContext.getUserId() == null
                || UserContext.getUsername() == null
                || UserContext.getAuthVersion() == null)
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Management session required");
        return new UserInfoDTO(
                UserContext.getUserId(),
                UserContext.getUsername(),
                null,
                UserContext.getAuthVersion());
    }

    private static <T> T bound(UserInfoDTO identity, java.util.function.Supplier<T> call) {
        var previous =
                UserContext.getUserId() == null
                        ? null
                        : new UserInfoDTO(
                                UserContext.getUserId(),
                                UserContext.getUsername(),
                                UserContext.getRealName(),
                                UserContext.getAuthVersion());
        UserContext.setUser(identity);
        try {
            return call.get();
        } finally {
            UserContext.removeUser();
            if (previous != null) UserContext.setUser(previous);
        }
    }

    static String safe(String value) {
        if (value == null) return "";
        int i = 0;
        while (i < value.length() && Character.isWhitespace(value.charAt(i))) i++;
        return i < value.length() && "=+-@".indexOf(value.charAt(i)) >= 0
                        || !value.isEmpty()
                                && (value.charAt(0) == '\t'
                                        || value.charAt(0) == '\r'
                                        || value.charAt(0) == '\n')
                ? "'" + value
                : value;
    }

    static String csv(String... values) {
        return Arrays.stream(values)
                        .map(BatchExportController::safe)
                        .map(v -> "\"" + v.replace("\"", "\"\"") + "\"")
                        .collect(java.util.stream.Collectors.joining(","))
                + "\r\n";
    }
}
