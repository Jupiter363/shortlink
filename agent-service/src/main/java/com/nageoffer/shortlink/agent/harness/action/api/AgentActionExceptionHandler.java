package com.nageoffer.shortlink.agent.harness.action.api;

import com.nageoffer.shortlink.agent.common.result.Result;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AgentPendingActionInternalController.class)
public class AgentActionExceptionHandler {

    private static final String FALLBACK_MESSAGE = "Agent action request failed";

    @ExceptionHandler(AgentActionException.class)
    public ResponseEntity<Result<Object>> handleAgentActionException(AgentActionException ex) {
        Result<Object> body = Result.failure(
                ex.code(),
                StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : FALLBACK_MESSAGE,
                null
        );
        return ResponseEntity.status(statusFor(ex.code())).body(body);
    }

    private HttpStatus statusFor(String code) {
        return switch (code) {
            case "ACTION_PAYLOAD_INVALID", "ACTION_REVIEW_ACTION_INVALID" ->
                    HttpStatus.BAD_REQUEST;
            case "ACTION_SCOPE_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "ACTION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ACTION_PAYLOAD_CONFLICT",
                    "ACTION_NOT_CONFIRMABLE",
                    "ACTION_VERSION_CONFLICT" -> HttpStatus.CONFLICT;
            case "ACTION_EXECUTING" -> HttpStatus.ACCEPTED;
            case "ACTION_EXECUTION_FAILED", "ACTION_EXECUTOR_UNAVAILABLE" ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
