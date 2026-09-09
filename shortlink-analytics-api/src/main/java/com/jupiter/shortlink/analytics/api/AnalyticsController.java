package com.jupiter.shortlink.analytics.api;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/internal/analytics/v1")
public class AnalyticsController {
    private final AnalyticsQueryService queries;
    private final ApiSettings settings;

    public AnalyticsController(AnalyticsQueryService q, ApiSettings s) {
        queries = q;
        settings = s;
    }

    @PostMapping("/query")
    public Map<String, Object> query(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody QueryRequest request) {
        if (token == null
                || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        settings.token().getBytes(StandardCharsets.UTF_8)))
            throw new QueryFailure("FORBIDDEN", "Internal authentication required");
        return Map.of("code", "0", "message", "success", "data", queries.query(request));
    }

    @ExceptionHandler(QueryFailure.class)
    public ResponseEntity<Map<String, Object>> failure(QueryFailure e) {
        return ResponseEntity.status(
                        e.code.equals("FORBIDDEN") ? HttpStatus.FORBIDDEN : HttpStatus.OK)
                .body(Map.of("code", e.code, "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unavailable(Exception e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        Map.of(
                                "code",
                                "UNAVAILABLE",
                                "message",
                                "Analytics is temporarily unavailable"));
    }
}
