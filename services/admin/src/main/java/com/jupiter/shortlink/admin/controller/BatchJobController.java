package com.jupiter.shortlink.admin.controller;

import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.remote.BatchCommandRemoteService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/short-link/admin/v1/batches")
public class BatchJobController {
    private final BatchCommandRemoteService commands;

    public BatchJobController(BatchCommandRemoteService commands) {
        this.commands = commands;
    }

    @PostMapping("/imports")
    public ResponseEntity<Result<BatchCommandRemoteService.Status>> submit(
            @RequestBody BatchCommandRemoteService.ImportRequest request) {
        return ResponseEntity.accepted().body(Results.success(commands.submitImport(request)));
    }

    @GetMapping("/{job}")
    public Result<BatchCommandRemoteService.Status> status(@PathVariable String job) {
        return Results.success(commands.status(job));
    }

    @GetMapping("/{job}/rows")
    public Result<List<BatchCommandRemoteService.Row>> results(
            @PathVariable String job,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        return Results.success(commands.results(job, after, limit));
    }

    @PostMapping("/{job}/cancel")
    public Result<BatchCommandRemoteService.Status> cancel(@PathVariable String job) {
        return Results.success(commands.cancel(job));
    }
}
