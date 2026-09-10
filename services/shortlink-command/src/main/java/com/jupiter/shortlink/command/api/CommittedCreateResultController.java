package com.jupiter.shortlink.command.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.link.LinkCommandService.Created;
import com.jupiter.shortlink.command.security.CommandAuthorization;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Reads the immutable creation receipt; this endpoint never allocates IDs or retries creation. */
@RestController
public class CommittedCreateResultController {
    private final CommandAuthorization auth;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CommittedCreateResultController(
            CommandAuthorization auth, JdbcTemplate jdbc, ObjectMapper json) {
        this.auth = auth;
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/internal/command/creates/result")
    public List<Created> result(@RequestParam String requestId, HttpServletRequest request) {
        var principal = auth.principal(request);
        if (requestId == null || requestId.isBlank() || requestId.length() > 96)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "requestId must be 1..96 characters");
        var rows =
                jdbc.queryForList(
                        "SELECT result_json FROM t_command_result WHERE tenant_id=? AND"
                                + " command_id=?",
                        principal.tenantId(),
                        "create:" + requestId);
        if (rows.isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Committed creation result unavailable");
        String payload = rows.get(0).get("result_json").toString();
        if (payload.length() > 2 * 1024 * 1024)
            throw new IllegalStateException("Stored creation result exceeds budget");
        try {
            List<Created> result = json.readValue(payload, new TypeReference<List<Created>>() {});
            if (result == null || result.isEmpty() || result.size() > 500)
                throw new IllegalStateException("Invalid committed creation result size");
            auth.check(principal, false);
            return List.copyOf(result);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Stored creation result corrupted", error);
        }
    }
}
