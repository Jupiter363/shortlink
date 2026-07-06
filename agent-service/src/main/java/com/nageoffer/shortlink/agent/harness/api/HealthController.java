package com.nageoffer.shortlink.agent.harness.api;

import com.nageoffer.shortlink.agent.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/internal/short-link-agent/v1/health")
    public Result<HealthResponse> health() {
        return Result.success(new HealthResponse("OK", "short-link-agent"));
    }

    public record HealthResponse(String status, String service) {
    }
}
