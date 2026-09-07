package com.jupiter.shortlink.admin.common.convention.web;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

import com.jupiter.shortlink.admin.common.convention.errorcode.BaseErrorCode;
import com.jupiter.shortlink.admin.common.convention.exception.AbstractException;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;

import jakarta.servlet.http.HttpServletRequest;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Optional;

/** 全局异常处理器 */
@Component("globalExceptionHandlerByAdmin")
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Result> statusException(
            org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode())
                .body(
                        Results.failure(
                                "HTTP_" + ex.getStatusCode().value(),
                                ex.getReason() == null ? "Request rejected" : ex.getReason()));
    }

    @ExceptionHandler(feign.FeignException.class)
    public org.springframework.http.ResponseEntity<Result> remoteException(
            feign.FeignException ex) {
        int status =
                java.util.Set.of(400, 401, 403, 404, 409, 410, 413, 429).contains(ex.status())
                        ? ex.status()
                        : 503;
        com.jupiter.shortlink.admin.config.AdminDependencyDiagnostics.feignFailure(ex, ex.status(), status);
        return org.springframework.http.ResponseEntity.status(status)
                .body(
                        Results.failure(
                                "REMOTE_" + status, "Remote request rejected or unavailable"));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public org.springframework.http.ResponseEntity<Result> invalidRequest(Exception ex) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(Results.failure("HTTP_400", "Invalid request"));
    }

    /** 拦截参数验证异常 */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result validExceptionHandler(
            HttpServletRequest request, MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        FieldError firstFieldError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        String exceptionStr =
                Optional.ofNullable(firstFieldError)
                        .map(FieldError::getDefaultMessage)
                        .orElse(StrUtil.EMPTY);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    /** 拦截应用内抛出的异常 */
    @ExceptionHandler(value = {AbstractException.class})
    public Result abstractException(HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            log.error(
                    "[{}] {} [ex] {}",
                    request.getMethod(),
                    request.getRequestURL().toString(),
                    ex.toString(),
                    ex.getCause());
            return Results.failure(ex);
        }
        log.error(
                "[{}] {} [ex] {}",
                request.getMethod(),
                request.getRequestURL().toString(),
                ex.toString());
        return Results.failure(ex);
    }

    /** 拦截未捕获异常 */
    @ExceptionHandler(value = Throwable.class)
    public org.springframework.http.ResponseEntity<Result> defaultErrorHandler(
            HttpServletRequest request, Throwable throwable) {
        log.error(
                "[{}] {} failed ({})",
                request.getMethod(),
                request.getRequestURI(),
                throwable.getClass().getSimpleName());
        return org.springframework.http.ResponseEntity.status(500).body(Results.failure());
    }

    private String getUrl(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
