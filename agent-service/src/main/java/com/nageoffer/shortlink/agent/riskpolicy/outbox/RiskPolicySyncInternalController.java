package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import com.nageoffer.shortlink.agent.common.result.Result;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.dto.RiskPolicySyncReplayReqDTO;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.dto.RiskPolicySyncReplayRespDTO;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/short-link-agent/v1/policy-sync")
public class RiskPolicySyncInternalController {

    private static final String USERNAME_HEADER = "X-Agent-Username";
    private static final String USER_ID_HEADER = "X-Agent-UserId";
    private static final String REAL_NAME_HEADER = "X-Agent-RealName";
    private static final String SCOPE_FORBIDDEN = "ACTION_SCOPE_FORBIDDEN";
    private static final String SCOPE_FORBIDDEN_MESSAGE = "Agent action access is forbidden";

    private final RiskPolicySyncService syncService;

    public RiskPolicySyncInternalController(RiskPolicySyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/outbox/{outboxId}/replay")
    public Result<RiskPolicySyncReplayRespDTO> replay(
            @PathVariable("outboxId") String outboxId,
            @RequestBody RiskPolicySyncReplayReqDTO request,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = REAL_NAME_HEADER, required = false) String realName
    ) {
        AgentActionActor actor = actor(username, userId, realName);
        RiskPolicySyncOutboxStatus status = syncService.replay(
                outboxId,
                actor,
                request == null ? null : request.reason()
        );
        return Result.success(new RiskPolicySyncReplayRespDTO(outboxId, status));
    }

    private AgentActionActor actor(String username, String userId, String realName) {
        if (!StringUtils.hasText(username)
                || !StringUtils.hasText(userId)
                || !StringUtils.hasText(realName)) {
            throw new AgentActionException(SCOPE_FORBIDDEN, SCOPE_FORBIDDEN_MESSAGE);
        }
        return new AgentActionActor(username, userId, realName, "");
    }
}
