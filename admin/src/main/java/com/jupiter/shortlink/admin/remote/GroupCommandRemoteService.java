package com.jupiter.shortlink.admin.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@FeignClient(
        name = "shortlink-command-groups",
        url = "${shortlink.command.base-url:http://127.0.0.1:8001}")
public interface GroupCommandRemoteService {
    record Group(
            String gid, String name, int sortOrder, long linkCount, long jobRefs, long revision) {}

    record Input(String gid, String name) {}

    @GetMapping("/internal/command/groups")
    List<Group> list();

    @PostMapping("/internal/command/groups")
    Group create(@RequestBody Input input);

    @PutMapping("/internal/command/groups")
    void rename(@RequestBody Input input);

    @DeleteMapping("/internal/command/groups/{gid}")
    void delete(@PathVariable("gid") String gid);

    @PutMapping("/internal/command/groups/order")
    void order(@RequestBody Map<String, Integer> order);
}
