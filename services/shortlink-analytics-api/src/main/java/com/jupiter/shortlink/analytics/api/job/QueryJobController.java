package com.jupiter.shortlink.analytics.api.job;

import com.jupiter.shortlink.analytics.api.*;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/internal/analytics/v1/jobs")
public class QueryJobController {
    private final QueryJobService jobs;
    private final ApiSettings settings;
    private final Semaphore exports = new Semaphore(2);

    public QueryJobController(QueryJobService jobs, ApiSettings settings) {
        this.jobs = jobs;
        this.settings = settings;
    }

    @PostMapping
    public Map<String, Object> submit(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody QueryJobService.Submit request) {
        token(token);
        scopeProtocol(request, false);
        return ok(jobs.submit(request));
    }

    @PostMapping("/recover-existing")
    public Map<String, Object> recoverExisting(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody QueryJobService.Submit request) {
        token(token);
        scopeProtocol(request, false);
        return ok(jobs.recoverExisting(request));
    }

    @PostMapping("/frozen")
    public Map<String, Object> submitFrozen(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody QueryJobService.Submit request) {
        token(token);
        scopeProtocol(request, true);
        return ok(jobs.submitFrozen(request));
    }

    @PostMapping("/frozen/recover-existing")
    public Map<String, Object> recoverExistingFrozen(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody QueryJobService.Submit request) {
        token(token);
        scopeProtocol(request, true);
        return ok(jobs.recoverExistingFrozen(request));
    }

    private static void scopeProtocol(QueryJobService.Submit request, boolean frozen) {
        if (request == null || request.query() == null || (request.query().scope() != null) != frozen)
            throw new QueryFailure("INVALID_QUERY", "Query scope does not match the submission protocol");
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> status(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody QueryJobService.Identity identity) {
        token(token);
        return ok(jobs.status(id, identity));
    }

    @PostMapping("/{id}/page")
    public Map<String, Object> page(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody QueryJobService.Identity identity) {
        token(token);
        return ok(jobs.page(id, identity));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody QueryJobService.Identity identity) {
        token(token);
        return ok(jobs.cancel(id, identity));
    }

    @PostMapping("/{id}/release-result")
    public Map<String, Object> releaseResult(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody QueryJobService.Submit request) {
        token(token);
        return ok(jobs.releaseResult(id, request));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/{id}/export")
    public void export(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody QueryJobService.Identity identity,
            HttpServletResponse response)
            throws IOException {
        token(token);
        if (!exports.tryAcquire()) throw new QueryFailure("TOO_LARGE", "Export capacity exhausted");
        try {
            var first =
                    jobs.page(
                            id,
                            new QueryJobService.Identity(
                                    identity.tenantId(),
                                    identity.subjectId(),
                                    identity.authVersion(),
                                    0,
                                    500));
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader(
                    "Content-Disposition", "attachment; filename=\"analytics-results.csv\"");
            response.setHeader("Cache-Control", "no-store, private");
            response.setHeader("X-Content-Type-Options", "nosniff");
            Writer writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    response.getOutputStream(), StandardCharsets.UTF_8),
                            8192);
            writer.write('\ufeff');
            List<String> fields =
                    List.of(
                            "day",
                            "eventId",
                            "linkId",
                            "occurredAt",
                            "kind",
                            "status",
                            "pv",
                            "uv",
                            "uip",
                            "denied",
                            "visitorHash",
                            "browser",
                            "os",
                            "device",
                            "country", "province", "city", "network", "geoStatus", "geoVersion", "uvType", "ipHash", "refererDomain");
            writer.write(String.join(",", fields) + "\r\n");
            Map<String, Object> page = first;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
            int index = 0;
            while (true) {
                if (System.nanoTime() > deadline)
                    throw new IOException("Export time budget exceeded");
                for (var item : (List<Map<String, Object>>) page.get("items")) {
                    List<String> values = new ArrayList<>();
                    for (String field : fields)
                        values.add(csv(Objects.toString(item.get(field), "")));
                    writer.write(String.join(",", values) + "\r\n");
                }
                writer.flush();
                var meta = (Map<String, Object>) page.get("meta");
                if (meta.get("nextPageIndex") == null) break;
                int next = ((Number) meta.get("nextPageIndex")).intValue();
                if (next != index + 1 || next > 400)
                    throw new IOException("Invalid export page cursor");
                index = next;
                page =
                        jobs.page(
                                id,
                                new QueryJobService.Identity(
                                        identity.tenantId(),
                                        identity.subjectId(),
                                        identity.authVersion(),
                                        index,
                                        500));
            }
        } finally {
            exports.release();
        }
    }

    private void token(String token) {
        if (token == null
                || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        settings.token().getBytes(StandardCharsets.UTF_8)))
            throw new QueryFailure("FORBIDDEN", "Internal authentication required");
    }

    private static Map<String, Object> ok(Object value) {
        return Map.of("code", "0", "message", "success", "data", value);
    }

    static String csv(String value) {
        int first = 0;
        while (first < value.length() && Character.isWhitespace(value.charAt(first))) first++;
        if (first < value.length() && "=+-@".indexOf(value.charAt(first)) >= 0
                || !value.isEmpty()
                        && (value.charAt(0) == '\t'
                                || value.charAt(0) == '\r'
                                || value.charAt(0) == '\n')) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @ExceptionHandler(QueryFailure.class)
    public ResponseEntity<Map<String, Object>> failure(QueryFailure error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.code);
        body.put("message", error.getMessage());
        body.putAll(error.details);
        return ResponseEntity.status(
                        error.code.equals("FORBIDDEN") ? HttpStatus.FORBIDDEN : HttpStatus.OK)
                .body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_QUERY", "message", "Query request body is invalid"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unavailable(Exception error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "UNAVAILABLE", "message", "Query job service unavailable"));
    }
}
