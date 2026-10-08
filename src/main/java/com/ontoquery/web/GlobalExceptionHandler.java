package com.ontoquery.web;

import com.ontoquery.ontology.ops.OpsRejectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常转译：错误响应统一 {code, message}，code 为 5 位错误码（A=用户端，B=系统端）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String CODE_BAD_REQUEST = "A0400";
    private static final String CODE_INTERNAL = "B0500";

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(CODE_BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(OpsRejectException.class)
    public ResponseEntity<Map<String, String>> opsReject(OpsRejectException e) {
        HttpStatus status = e.getCode().startsWith("A") ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(body(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(CODE_INTERNAL, "服务内部错误: " + e.getMessage()));
    }

    private Map<String, String> body(String code, String message) {
        Map<String, String> map = new LinkedHashMap<>(4);
        map.put("code", code);
        map.put("message", message);
        return map;
    }
}
