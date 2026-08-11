package com.nageoffer.shortlink.admin.authority.capability.api;

import com.nageoffer.shortlink.admin.authority.capability.exception.AgentCapabilityException;
import com.nageoffer.shortlink.admin.authority.capability.model.AgentCapabilityProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Locale;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AgentCapabilityInternalController.class)
public class AgentCapabilityExceptionHandler {

    @ExceptionHandler(AgentCapabilityException.class)
    public ResponseEntity<AgentCapabilityProblem> handleCapabilityException(
            AgentCapabilityException exception,
            HttpServletRequest request
    ) {
        return problem(
                request,
                exception.status(),
                exception.code(),
                exception.getMessage(),
                exception.retryable()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AgentCapabilityProblem> handleUnreadableRequest(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request body is invalid.",
                false
        );
    }

    private ResponseEntity<AgentCapabilityProblem> problem(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail,
            boolean retryable
    ) {
        AgentCapabilityProblem problem = new AgentCapabilityProblem(
                "https://shortlink.example/problems/agent/" + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                "Agent capability request failed",
                status.value(),
                code,
                detail,
                request.getRequestURI(),
                AgentCapabilityRequestIds.safeOrNull(request.getHeader("X-Agent-Trace-ID")),
                AgentCapabilityRequestIds.resolve(request.getHeader("X-Request-ID")),
                retryable,
                List.of()
        );
        return ResponseEntity.status(status).body(problem);
    }
}
