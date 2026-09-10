package com.jupiter.shortlink.command.api;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
public class CommandErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "success",
                                false,
                                "code",
                                "INVALID_REQUEST",
                                "message",
                                e.getMessage() == null ? "Invalid request" : e.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> database(org.springframework.dao.DataAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("success", false, "code", "STORE_UNAVAILABLE"));
    }
}
