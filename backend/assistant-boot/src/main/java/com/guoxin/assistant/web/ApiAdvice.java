package com.guoxin.assistant.web;

import com.guoxin.assistant.infrastructure.AgentStore;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.guoxin.assistant.infrastructure.AgentStore.map;

@RestControllerAdvice
public class ApiAdvice {
    private final Logger log = LoggerFactory.getLogger(getClass());

    @ExceptionHandler(
            org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    void disconnected(
            org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        log.debug("Client disconnected from async response");
    }

    @ExceptionHandler(AuthService.ApiException.class)
    ResponseEntity<?> api(AuthService.ApiException e) {
        return error(e.status, e.code, e.getMessage());
    }

    @ExceptionHandler(AgentStore.ResourceMissing.class)
    ResponseEntity<?> missing() {
        return error(404, "NOT_FOUND", "资源不存在或不属于当前用户");
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    ResponseEntity<?> invalid(Exception e) {
        return error(422, "INVALID_REQUEST", "请求参数不完整或格式不正确");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unknown(Exception e) {
        String trace = UUID.randomUUID().toString();
        log.error("Request failed trace={}", trace, e);
        return ResponseEntity.status(500)
                .body(
                        map(
                                "code",
                                "INTERNAL_ERROR",
                                "category",
                                "INTERNAL",
                                "message",
                                "服务暂时无法处理，请稍后重试",
                                "traceId",
                                trace,
                                "recoveryAction",
                                "RETRY_LATER"));
    }

    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status)
                .body(
                        map(
                                "code",
                                code,
                                "category",
                                status == 409 ? "CONFLICT" : status == 422 ? "VALIDATION" : "AUTH",
                                "message",
                                message,
                                "traceId",
                                UUID.randomUUID().toString(),
                                "recoveryAction",
                                status == 401
                                        ? "RELOGIN"
                                        : status == 409 ? "REFRESH_PREVIEW" : "NONE"));
    }
}
