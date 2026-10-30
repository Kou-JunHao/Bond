package com.bond.common.exception;

import com.bond.common.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        // 业务 code 与 HTTP 状态对齐：413（空间不足）等语义能直达客户端；未知 code 回退 400
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null || !status.isError()) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** 乐观锁冲突：409 + data 携带服务端版本/最后编辑人/时间，客户端据此选择“覆盖”或“重新加载”。 */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Result<Object>> handleConflict(ConflictException e) {
        Result<Object> body = Result.fail(e.getCode(), e.getMessage());
        body.setData(e.getData());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 缺少必填 query/form 参数：4xx 而不是 500。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, "缺少必填参数: " + e.getParameterName()));
    }

    /** 参数类型不匹配（如 path 里的数字传了字母）：4xx 而不是 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, "参数类型不正确: " + e.getName()));
    }

    /** 请求体 JSON 解析失败：4xx 而不是 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, "请求体格式不正确"));
    }

    /**
     * 不存在的路由/静态资源（Spring 6.1+ 的 NoResourceFoundException）：404 而不是被
     * {@link #handleGeneral(Exception)} 兜成 500。
     * <p>典型场景：客户端按过时文档调 {@code DELETE /api/trash/{id}}（该路由从未存在）。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "资源不存在: " + e.getResourcePath()));
    }

    /** 限流：429 + Retry-After（聊天发送等每用户/分钟限制）。 */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Result<Void>> handleRateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(Result.fail(429, e.getMessage()));
    }

    /** multipart 解析阶段超过 Spring max-file-size / max-request-size：413 而不是 500。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.fail(413, "文件超出服务端上传上限"));
    }

    /** 不支持的 HTTP 方法（如对只读模板用 PATCH）：405 + Allow，避免被兜底成 500。 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        String allow = e.getSupportedHttpMethods() == null ? "" :
                e.getSupportedHttpMethods().stream()
                        .map(org.springframework.http.HttpMethod::name)
                        .collect(java.util.stream.Collectors.joining(", "));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (!allow.isBlank()) {
            builder.header(HttpHeaders.ALLOW, allow);
        }
        return builder.body(Result.fail(405, "不支持的请求方法: " + e.getMethod()));
    }

    /**
     * 数据库完整性/约束冲突（如 subscriptions.no_overlap 排他约束）：409 + 中文可诊断话术。
     * <p>task-109：这类异常此前会落到 {@link #handleGeneral(Exception)} 被兜成 500，
     * 用户只看到“服务器内部错误”。约束名只进服务端日志，不下发客户端（避免泄漏 schema）。</p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
        String constraint = "unknown";
        if (detail != null) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("constraint \"([^\"]+)\"").matcher(detail);
            if (matcher.find()) {
                constraint = matcher.group(1);
            }
        }
        log.warn("data integrity violation mapped to 409 (constraint={}): {}", constraint, detail);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.fail(409, "数据冲突，请检查输入后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleGeneral(Exception e) {
        // task-99：兜底响应不得把 e.getMessage()（类名/字段名/空指针语义）下发给客户端；
        // 统一话术 + 可回查的 traceId，完整堆栈只进服务端日志。
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.error("unhandled server error traceId={}: {}", traceId, e.toString(), e);
        Result<Object> body = Result.fail(500, "服务器内部错误，请稍后重试");
        body.setData(Map.of("traceId", traceId));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
