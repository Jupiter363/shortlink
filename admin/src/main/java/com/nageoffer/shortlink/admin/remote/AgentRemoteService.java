package com.nageoffer.shortlink.admin.remote;

import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.remote.dto.req.AgentChatReqDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(value = "short-link-agent", url = "${short-link.agent.admin.remote-url:}")
public interface AgentRemoteService {

    @PostMapping("/internal/short-link-agent/v1/chat")
    Result<Object> chat(@RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
                        @RequestHeader(value = "X-Agent-Username") String username,
                        @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
                        @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
                        @RequestBody AgentChatReqDTO requestParam);

    @GetMapping("/internal/short-link-agent/v1/health")
    Result<Object> health(@RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
                          @RequestHeader(value = "X-Agent-Username") String username,
                          @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
                          @RequestHeader(value = "X-Agent-RealName", required = false) String realName);
}
