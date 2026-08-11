package com.nageoffer.shortlink.admin.authority.identity.api;

import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentIdentityProblem;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        AgentJwksController.class,
        AgentTokenExchangeController.class,
        AgentSessionController.class,
        AgentTokenRevocationController.class
})
public class AgentIdentityExceptionHandler {

    @ExceptionHandler(AgentIdentityException.class)
    public ResponseEntity<AgentIdentityProblem> handleIdentityException(
            AgentIdentityException exception,
            HttpServletRequest request
    ) {
        return problem(request, exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(AgentSessionGrantException.class)
    public ResponseEntity<AgentIdentityProblem> handleSessionGrantException(
            AgentSessionGrantException exception,
            HttpServletRequest request
    ) {
        return switch (exception.reason()) {
            case INVALID -> problem(
                    request,
                    HttpStatus.BAD_REQUEST,
                    "AGENT_SESSION_REQUEST_INVALID",
                    exception.getMessage()
            );
            case NOT_FOUND -> problem(
                    request,
                    HttpStatus.NOT_FOUND,
                    "AGENT_SESSION_NOT_FOUND",
                    exception.getMessage()
            );
            case FORBIDDEN -> problem(
                    request,
                    HttpStatus.NOT_FOUND,
                    "AGENT_SESSION_NOT_FOUND",
                    "Agent session grant does not exist."
            );
            case INACTIVE -> problem(
                    request,
                    HttpStatus.CONFLICT,
                    "AGENT_SESSION_INACTIVE",
                    exception.getMessage()
            );
            case EXPIRED -> problem(
                    request,
                    HttpStatus.GONE,
                    "AGENT_SESSION_EXPIRED",
                    exception.getMessage()
            );
            case CONFLICT -> problem(
                    request,
                    HttpStatus.CONFLICT,
                    "AGENT_SESSION_VERSION_CONFLICT",
                    exception.getMessage()
            );
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AgentIdentityProblem> handleUnreadableBody(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "AGENT_SESSION_REQUEST_INVALID",
                "Agent session request body is invalid."
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<AgentIdentityProblem> handleMissingParameter(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "TOKEN_EXCHANGE_INVALID",
                "Token exchange parameters are invalid."
        );
    }

    private ResponseEntity<AgentIdentityProblem> problem(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail
    ) {
        AgentIdentityProblem body = new AgentIdentityProblem(
                "https://shortlink.example/problems/agent/" + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                "Agent identity request failed",
                status.value(),
                code,
                detail,
                request.getRequestURI(),
                requestId(request.getHeader("X-Request-ID")),
                status.is5xxServerError(),
                List.of()
        );
        return ResponseEntity.status(status).body(body);
    }

    private String requestId(String candidate) {
        if (StringUtils.hasText(candidate) && candidate.length() <= 128) {
            return candidate;
        }
        return "req-" + UUID.randomUUID().toString().replace("-", "");
    }
}
