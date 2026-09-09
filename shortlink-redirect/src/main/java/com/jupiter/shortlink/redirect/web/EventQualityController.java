package com.jupiter.shortlink.redirect.web;

import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.redirect.event.RequestEventPublisher;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
public final class EventQualityController {
    private final RequestEventPublisher events;
    private final byte[] token;

    public EventQualityController(RequestEventPublisher events, RedirectProperties properties) {
        this.events = events;
        token = properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/v1/events/quality")
    public Map<String, Object> quality(
            @RequestHeader(value = "X-Internal-Token", defaultValue = "") String supplied) {
        if (!MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return events.quality();
    }
}
