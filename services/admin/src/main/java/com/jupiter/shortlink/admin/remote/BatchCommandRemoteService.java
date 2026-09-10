package com.jupiter.shortlink.admin.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "batch-command", url = "${shortlink.command.base-url:http://127.0.0.1:8001}")
public interface BatchCommandRemoteService {
    record ObjectReference(String bucket, String key, String version, String sha256, long bytes) {}

    record ImportRequest(String requestId, String gid, ObjectReference object) {}

    record Status(
            String jobId,
            String state,
            long totalRows,
            long validRows,
            long invalidRows,
            long succeededRows,
            long failedRows,
            String error,
            String checksum,
            Long actualBytes) {}

    record Row(long row, String state, Long linkId, Map<String, Object> result, String error) {}

    @PostMapping("/internal/command/batches/imports")
    Status submitImport(@RequestBody ImportRequest request);

    @GetMapping("/internal/command/batches/{job}")
    Status status(@PathVariable("job") String job);

    @GetMapping("/internal/command/batches/{job}/rows")
    List<Row> results(
            @PathVariable("job") String job,
            @RequestParam("after") long after,
            @RequestParam("limit") int limit);

    @PostMapping("/internal/command/batches/{job}/cancel")
    Status cancel(@PathVariable("job") String job);
}
