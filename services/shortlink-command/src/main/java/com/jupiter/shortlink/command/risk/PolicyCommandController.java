package com.jupiter.shortlink.command.risk;

import com.jupiter.shortlink.command.security.CommandAuthorization;
import com.jupiter.shortlink.risk.PolicySnapshot;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class PolicyCommandController {
    private final PolicyCommandService service;
    private final CommandAuthorization auth;

    public PolicyCommandController(PolicyCommandService service, CommandAuthorization auth) {
        this.service = service;
        this.auth = auth;
    }

    @PostMapping("/internal/command/risk/activate")
    public PolicyCommandService.CommandResult activate(
            @RequestBody PolicyCommandService.Mutation q, HttpServletRequest r) {
        return service.activate(auth.principal(r), q);
    }

    @PostMapping("/internal/command/risk/revoke")
    public PolicyCommandService.CommandResult revoke(
            @RequestBody PolicyCommandService.Revocation q, HttpServletRequest r) {
        return service.revoke(auth.principal(r), q);
    }

    @GetMapping("/internal/command/risk/commands/{commandId}")
    public PolicyCommandService.CommandResult result(
            @PathVariable String commandId, HttpServletRequest r) {
        return service.result(auth.principal(r), commandId);
    }

    public record Current(List<Long> linkIds) {}

    @GetMapping("/internal/command/risk/policies")
    public PolicyCommandService.PolicyPage policies(
            @RequestParam long linkId,
            @RequestParam(required = false) String afterPolicyId,
            HttpServletRequest r) {
        return service.policyPage(auth.principal(r), linkId, afterPolicyId);
    }

    @PostMapping("/internal/command/risk/current")
    public List<PolicySnapshot> current(@RequestBody Current q, HttpServletRequest r) {
        var p = auth.principal(r);
        if (q.linkIds() == null || q.linkIds().size() > 100)
            throw new IllegalArgumentException("Policy batch limit is 100");
        return q.linkIds().stream().map(id -> service.snapshot(p.tenantId(), id)).toList();
    }

    @GetMapping("/internal/short-link-command/v1/risk/ready")
    public Map<String, Boolean> ready(HttpServletRequest r) {
        auth.requireService(r);
        return Map.of("ready", service.ready());
    }

    @GetMapping("/internal/short-link-command/v1/risk/resources/{tenant}/{link}")
    public PolicySnapshot snapshot(
            @PathVariable long tenant, @PathVariable long link, HttpServletRequest r) {
        auth.requireService(r);
        return service.snapshot(tenant, link);
    }

    @PostMapping("/internal/v1/analytics/recovery/pause")
    public Map<String, Long> pause(HttpServletRequest r) {
        auth.requireService(r);
        return Map.of("gateFence", service.pause());
    }

    public record Epoch(String recoveryEpoch, long gateFence) {}

    @PostMapping("/internal/v1/analytics/recovery/activate")
    public void activateEpoch(@RequestBody Epoch e, HttpServletRequest r) {
        auth.requireService(r);
        service.activateEpoch(e.recoveryEpoch(), e.gateFence());
    }
}
