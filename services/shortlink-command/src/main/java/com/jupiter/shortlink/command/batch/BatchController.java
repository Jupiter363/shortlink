package com.jupiter.shortlink.command.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.link.LinkCommandService.Creation;
import com.jupiter.shortlink.command.security.CommandAuthorization;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/internal/command/batches")
public class BatchController {
    private final BatchJobService jobs;
    private final CommandAuthorization auth;
    private final ObjectMapper json;

    public BatchController(BatchJobService jobs, CommandAuthorization auth, ObjectMapper json) {
        this.jobs = jobs;
        this.auth = auth;
        this.json = json;
    }

    public record InlineRequest(String requestId, List<Creation> rows) {}

    @PostMapping
    public BatchJobService.Status inline(HttpServletRequest request) throws IOException {
        var p = auth.principal(request);
        InlineRequest input = readBounded(request, 8 * 1024 * 1024, InlineRequest.class);
        return jobs.submitInline(p, input.requestId(), input.rows());
    }

    @PostMapping("/imports")
    public BatchJobService.Status object(HttpServletRequest request) throws IOException {
        var p = auth.principal(request);
        return jobs.submitImport(
                p, readBounded(request, 16384, BatchJobService.ImportRequest.class));
    }

    @GetMapping("/{job}")
    public BatchJobService.Status status(HttpServletRequest request, @PathVariable String job) {
        return jobs.get(auth.principal(request), job);
    }

    @GetMapping("/{job}/rows")
    public List<BatchJobService.RowResult> results(
            HttpServletRequest request,
            @PathVariable String job,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        return jobs.results(auth.principal(request), job, after, limit);
    }

    @PostMapping("/{job}/cancel")
    public BatchJobService.Status cancel(HttpServletRequest request, @PathVariable String job) {
        return jobs.cancel(auth.principal(request), job);
    }

    private <T> T readBounded(HttpServletRequest request, int max, Class<T> type)
            throws IOException {
        if (request.getContentLengthLong() > max)
            throw new IllegalArgumentException("Request byte budget exceeded");
        byte[] bytes = request.getInputStream().readNBytes(max + 1);
        if (bytes.length > max) throw new IllegalArgumentException("Request byte budget exceeded");
        return json.readValue(bytes, type);
    }
}
