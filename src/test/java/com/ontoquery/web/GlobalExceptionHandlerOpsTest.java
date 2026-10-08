package com.ontoquery.web;

import com.ontoquery.ontology.ops.OpsRejectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerOpsTest {

    @Test
    void 用户端错误码返回400() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<Map<String, String>> resp = handler.opsReject(new OpsRejectException("A0403", "核心保护"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), "A 码应为 400");
        assertEquals("A0403", resp.getBody().get("code"), "body 携带错误码");
    }

    @Test
    void 系统端错误码返回500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<Map<String, String>> resp = handler.opsReject(new OpsRejectException("B0501", "校验失败"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode(), "B 码应为 500");
    }
}
