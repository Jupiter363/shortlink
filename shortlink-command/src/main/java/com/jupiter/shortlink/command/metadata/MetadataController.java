package com.jupiter.shortlink.command.metadata;

import com.jupiter.shortlink.command.security.CommandAuthorization;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/internal/command/metadata")
public class MetadataController {
    private final SafeMetadataFetcher fetcher;
    private final CommandAuthorization auth;

    public MetadataController(SafeMetadataFetcher fetcher, CommandAuthorization auth) {
        this.fetcher = fetcher;
        this.auth = auth;
    }

    @GetMapping("/title")
    public String title(HttpServletRequest request, @RequestParam String url) throws IOException {
        auth.principal(request);
        return fetcher.fetch(url).title();
    }
}
