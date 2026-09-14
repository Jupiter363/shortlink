package com.jupiter.shortlink.command.batch;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.security.CommandAuthorization;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps the existing management path while making synchronous versus durable asynchronous results
 * explicit.
 */
@RestController
public class BatchCompatibilityController {
    public record Input(
            String requestId,
            String domain,
            List<String> originUrls,
            List<String> describes,
            String gid,
            Integer createdType,
            Integer validDateType,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") Date validDate) {}

    public record Result<T>(String code, T data) {}

    public record Output(
            Integer total,
            List<Map<String, Object>> baseLinkInfos,
            String jobId,
            String state,
            String resultUrl) {}

    private final BatchJobService jobs;
    private final LinkCommandService links;
    private final CommandAuthorization auth;
    private final ObjectMapper json;
    private final String domain;

    public BatchCompatibilityController(
            BatchJobService jobs,
            LinkCommandService links,
            CommandAuthorization auth,
            ObjectMapper json,
            @Value("${shortlink.default-domain}") String domain) {
        this.jobs = jobs;
        this.links = links;
        this.auth = auth;
        this.json = json;
        this.domain = domain;
    }

    @PostMapping("/api/short-link/v1/create/batch")
    public CompletableFuture<ResponseEntity<Result<Output>>> create(HttpServletRequest request)
            throws IOException {
        var p = auth.principal(request);
        int limit = 8 * 1024 * 1024;
        if (request.getContentLengthLong() > limit)
            throw new IllegalArgumentException("Inline byte budget exceeded; use immutable import");
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit)
            throw new IllegalArgumentException("Inline byte budget exceeded; use immutable import");
        Input input = json.readValue(body, Input.class);
        if (input.originUrls() == null
                || input.originUrls().size() < 2
                || input.originUrls().size() > 50000)
            throw new IllegalArgumentException(
                    "Inline batch rows must be 2..50000; larger imports require a fixed object"
                            + " version");
        if (input.describes() != null && input.describes().size() != input.originUrls().size())
            throw new IllegalArgumentException("Description count must match origin URLs");
        String requestId =
                input.requestId() == null
                        ? request.getHeader("Idempotency-Key")
                        : input.requestId();
        List<LinkCommandService.Creation> rows = new ArrayList<>(input.originUrls().size());
        for (int i = 0; i < input.originUrls().size(); i++)
            rows.add(
                    new LinkCommandService.Creation(
                            input.domain() == null ? domain : input.domain(),
                            input.originUrls().get(i),
                            input.gid(),
                            input.createdType() == null ? 0 : input.createdType(),
                            input.validDateType() == null ? 0 : input.validDateType(),
                            input.validDate() == null ? null : input.validDate().getTime(),
                            input.describes() == null ? null : input.describes().get(i)));
        if (rows.size() <= 500) {
            return com.jupiter.shortlink.command.membership.RoutePublication.map(
                    links.createManyAsync(p, requestId, rows),
                    result -> {
                        List<Map<String, Object>> infos = new ArrayList<>();
                        for (int i = 0; i < result.size(); i++) {
                            var row = result.get(i);
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("linkId", row.linkId());
                            info.put("fullShortUrl", row.fullShortUrl());
                            info.put("originUrl", row.originUrl());
                            info.put("describe", rows.get(i).describe());
                            infos.add(info);
                        }
                        return ResponseEntity.ok(
                                new Result<>(
                                        "0",
                                        new Output(result.size(), infos, null, "SUCCEEDED", null)));
                    });
        }
        var status = jobs.submitInline(p, requestId, rows);
        return CompletableFuture.completedFuture(
                ResponseEntity.accepted()
                        .body(
                                new Result<>(
                                        "0",
                                        new Output(
                                                null,
                                                List.of(),
                                                status.jobId(),
                                                status.state(),
                                                "/internal/command/batches/"
                                                        + status.jobId()
                                                        + "/rows"))));
    }
}
