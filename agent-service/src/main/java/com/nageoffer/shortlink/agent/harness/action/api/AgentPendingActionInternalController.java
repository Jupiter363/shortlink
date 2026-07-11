package com.nageoffer.shortlink.agent.harness.action.api;

import com.nageoffer.shortlink.agent.common.result.Result;
import com.nageoffer.shortlink.agent.harness.action.api.dto.AgentActionConfirmReqDTO;
import com.nageoffer.shortlink.agent.harness.action.api.dto.AgentActionPageRespDTO;
import com.nageoffer.shortlink.agent.harness.action.api.dto.AgentActionRejectReqDTO;
import com.nageoffer.shortlink.agent.harness.action.api.dto.AgentPendingActionRespDTO;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentPendingActionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/short-link-agent/v1/actions")
public class AgentPendingActionInternalController {

    private static final String USERNAME_HEADER = "X-Agent-Username";
    private static final String USER_ID_HEADER = "X-Agent-UserId";
    private static final String REAL_NAME_HEADER = "X-Agent-RealName";
    private static final String SCOPE_FORBIDDEN_CODE = "ACTION_SCOPE_FORBIDDEN";
    private static final String SCOPE_FORBIDDEN_MESSAGE = "Agent action access is forbidden";
    private static final String EXECUTING_CODE = "ACTION_EXECUTING";
    private static final String EXECUTING_MESSAGE = "Agent action is executing";
    private static final int MAX_PAGE_SIZE = 100;

    private final AgentPendingActionService service;

    public AgentPendingActionInternalController(AgentPendingActionService service) {
        this.service = service;
    }

    @GetMapping
    public Result<AgentActionPageRespDTO> list(
            @RequestParam("gid") String gid,
            @RequestParam(value = "agentType", required = false) String agentType,
            @RequestParam(value = "actionType", required = false) String actionType,
            @RequestParam(value = "status", required = false) AgentActionStatus status,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = REAL_NAME_HEADER, required = false) String realName
    ) {
        AgentActionActor actor = actor(username, userId, realName, gid);
        return Result.success(AgentActionPageRespDTO.from(service.page(
                gid,
                agentType,
                actionType,
                status,
                pageNo,
                Math.min(pageSize, MAX_PAGE_SIZE),
                actor
        )));
    }

    @GetMapping("/{actionId}")
    public Result<AgentPendingActionRespDTO> detail(
            @PathVariable("actionId") String actionId,
            @RequestParam("expectedGid") String expectedGid,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = REAL_NAME_HEADER, required = false) String realName
    ) {
        AgentActionActor actor = actor(username, userId, realName, expectedGid);
        return Result.success(AgentPendingActionRespDTO.from(service.detail(actionId, actor)));
    }

    @PostMapping("/{actionId}/confirm")
    public ResponseEntity<Result<AgentPendingActionRespDTO>> confirm(
            @PathVariable("actionId") String actionId,
            @RequestBody AgentActionConfirmReqDTO request,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = REAL_NAME_HEADER, required = false) String realName
    ) {
        AgentActionActor actor = actor(username, userId, realName, request.expectedGid());
        AgentPendingActionView view = service.confirm(
                actionId,
                request.expectedVersion(),
                actor,
                request.note()
        );
        AgentPendingActionRespDTO response = AgentPendingActionRespDTO.from(view);
        if (view.status() == AgentActionStatus.EXECUTING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Result.failure(EXECUTING_CODE, EXECUTING_MESSAGE, response));
        }
        return ResponseEntity.ok(Result.success(response));
    }

    @PostMapping("/{actionId}/reject")
    public Result<AgentPendingActionRespDTO> reject(
            @PathVariable("actionId") String actionId,
            @RequestBody AgentActionRejectReqDTO request,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = REAL_NAME_HEADER, required = false) String realName
    ) {
        AgentActionActor actor = actor(username, userId, realName, request.expectedGid());
        return Result.success(AgentPendingActionRespDTO.from(service.reject(
                actionId,
                request.expectedVersion(),
                actor,
                request.reason(),
                request.reviewAction()
        )));
    }

    private AgentActionActor actor(
            String username,
            String userId,
            String realName,
            String expectedGid
    ) {
        if (!StringUtils.hasText(username)) {
            throw new AgentActionException(SCOPE_FORBIDDEN_CODE, SCOPE_FORBIDDEN_MESSAGE);
        }
        return new AgentActionActor(username, userId, realName, expectedGid);
    }
}
