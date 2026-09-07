package com.jupiter.shortlink.admin.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Uses the shared ManagementFeignIdentity interceptor; no caller-supplied identity is accepted. */
@FeignClient(
        name = "shortlink-command-risk",
        url = "${shortlink.command.base-url:http://127.0.0.1:8001}",
        configuration = CommandRiskRemoteService.Transport.class)
public interface CommandRiskRemoteService {
    class Transport {
        @org.springframework.context.annotation.Bean
        public feign.Request.Options options() {
            return new feign.Request.Options(
                    1000,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    2000,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    false);
        }

        @org.springframework.context.annotation.Bean
        public feign.Retryer retryer() {
            return feign.Retryer.NEVER_RETRY;
        }
    }

    record Current(List<Long> linkIds) {}

    record Revoke(String commandId, long linkId, String policyId) {}

    record Receipt(
            String commandId,
            String status,
            String policyId,
            long policyRevision,
            long committedAt) {}

    record Snapshot(
            String resourceKey,
            long policyRevision,
            long evaluatedAt,
            Long nextTransitionAt,
            long validUntil,
            String state,
            boolean disabled,
            boolean timeUnrestricted,
            String timezone,
            List<Map<String, Object>> allowedWindows,
            List<String> blockedIpHashes,
            Map<String, Object> rateLimit) {}

    @PostMapping("/internal/command/risk/current")
    List<Snapshot> current(@RequestBody Current request);

    @PostMapping("/internal/command/risk/activate")
    Receipt activate(@RequestBody Map<String, Object> request);

    @PostMapping("/internal/command/risk/revoke")
    Receipt revoke(@RequestBody Revoke request);

    @GetMapping("/internal/command/risk/commands/{commandId}")
    Receipt result(@PathVariable("commandId") String commandId);

    @GetMapping("/internal/command/risk/policies")
    Map<String, Object> policies(
            @RequestParam("linkId") long linkId,
            @RequestParam(value = "afterPolicyId", required = false) String cursor);
}
