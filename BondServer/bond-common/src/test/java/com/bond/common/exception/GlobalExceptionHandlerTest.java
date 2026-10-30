package com.bond.common.exception;

import com.bond.common.dto.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * task-21：参数缺失/类型不匹配不再被兜成 500。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("缺少必填参数 → 400（而不是 500）")
    void missingRequestParameterReturns400() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("token", "String");

        var response = handler.handleMissingParameter(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(400, body.getCode());
    }

    @Test
    @DisplayName("业务 413（空间不足）→ HTTP 413，客户端可直接识别")
    void business413MapsToHttp413() {
        var response = handler.handleBusiness(new BusinessException(413, "空间不足"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(413, body.getCode());
        assertEquals("空间不足", body.getMessage());
    }

    @Test
    @DisplayName("multipart 超过 Spring 硬上限 → 413（而不是 500）")
    void maxUploadSizeMapsTo413() {
        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(20L * 1024 * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(413, body.getCode());
    }

    @Test
    @DisplayName("未映射到 HTTP 状态码的业务 code → HTTP 回退 400，业务 code 原样保留")
    void unknownBusinessCodeFallsBackTo400() {
        var response = handler.handleBusiness(new BusinessException(1001, "自定义业务错误"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(1001, response.getBody().getCode());
    }

    @Test
    @DisplayName("task-109：数据库约束冲突 → 409 中文话术，约束名只进服务端日志")
    void dataIntegrityViolationMapsTo409() {
        DataIntegrityViolationException conflict = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: conflicting key value violates exclusion constraint \"no_overlap\""));

        var response = handler.handleDataIntegrity(conflict);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(409, body.getCode());
        assertEquals("数据冲突，请检查输入后重试", body.getMessage());
        // 约束名/schema 细节不得下发
        assertFalse(body.getMessage().contains("no_overlap"));
        assertFalse(body.getMessage().contains("constraint"));
    }

    @Test
    @DisplayName("task-99：兜底 500 不下发 e.getMessage()，改为统一话术 + traceId")
    void catchAllHidesInternalDetail() {
        var response = handler.handleGeneral(new NullPointerException(
                "Cannot invoke \"java.lang.Integer.intValue()\" because the return value of "
                        + "\"...CreateTaskRequest.getChunkSize()\" is null"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Result<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(500, body.getCode());
        assertEquals("服务器内部错误，请稍后重试", body.getMessage());
        assertNotNull(body.getData());
        String data = String.valueOf(body.getData());
        assertEquals(false, data.contains("java."));
        assertEquals(false, data.contains("NullPointer"));
        assertEquals(false, data.contains("getChunkSize"));
        assertEquals(true, data.contains("traceId"));
    }
}
