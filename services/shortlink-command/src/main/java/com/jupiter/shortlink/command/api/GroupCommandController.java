package com.jupiter.shortlink.command.api;

import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.security.CommandAuthorization;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class GroupCommandController {
    private final CommandAuthorization auth;
    private final GroupCommandService groups;

    public GroupCommandController(CommandAuthorization auth, GroupCommandService groups) {
        this.auth = auth;
        this.groups = groups;
    }

    public record DefaultGroup(long tenantId, String username, String commandId, String name) {}

    public record GroupInput(String gid, String name) {}

    @PostMapping("/internal/command/accounts/default-group")
    public Map<String, String> initialize(@RequestBody DefaultGroup q, HttpServletRequest r) {
        auth.requireService(r);
        return Map.of(
                "gid", groups.defaultGroup(q.tenantId(), q.username(), q.commandId(), q.name()));
    }

    @GetMapping("/internal/command/groups")
    public List<GroupCommandService.Group> list(HttpServletRequest r) {
        return groups.list(auth.principal(r));
    }

    @PostMapping("/internal/command/groups")
    public GroupCommandService.Group create(@RequestBody GroupInput q, HttpServletRequest r) {
        return groups.create(auth.principal(r), q.name());
    }

    @PutMapping("/internal/command/groups")
    public void rename(@RequestBody GroupInput q, HttpServletRequest r) {
        groups.rename(auth.principal(r), q.gid(), q.name());
    }

    @DeleteMapping("/internal/command/groups/{gid}")
    public void delete(@PathVariable String gid, HttpServletRequest r) {
        groups.delete(auth.principal(r), gid);
    }

    @PutMapping("/internal/command/groups/order")
    public void order(@RequestBody Map<String, Integer> order, HttpServletRequest r) {
        groups.sort(auth.principal(r), order);
    }
}
