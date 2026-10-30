package com.bond.transfer.audit;

import com.bond.common.entity.User;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 管理操作审计切面（任务单 #19）。
 *
 * <p>在 {@link AdminAudit} 标注的方法成功执行后写入 audit_logs；审计动作本身失败只 warn，
 * 不改变请求结果。操作人来自网关注入的 X-User-Id（AdminInterceptor 已保证是管理员）。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AdminAuditAspect {

    private static final int MAX_BODY_CHARS = 1000;
    private static final int MAX_QUERY_CHARS = 200;
    private static final int MAX_IP_CHARS = 64;

    private final AuditService auditService;

    private final UserMapper userMapper;

    private final ObjectMapper objectMapper;

    @Around("@annotation(adminAudit)")
    public Object around(ProceedingJoinPoint joinPoint, AdminAudit adminAudit) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            record(joinPoint, adminAudit);
        } catch (RuntimeException e) {
            // 审计失败绝不能影响管理操作本身。
            log.warn("admin audit failed: action={}, error={}", adminAudit.action(), e.toString());
        }
        return result;
    }

    private void record(ProceedingJoinPoint joinPoint, AdminAudit adminAudit) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Object[] args = joinPoint.getArgs();
        Annotation[][] parameterAnnotations = signature.getMethod().getParameterAnnotations();

        Long targetId = null;
        Object body = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof HttpServletRequest) {
                continue;
            }
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof PathVariable && args[i] instanceof Number number) {
                    targetId = number.longValue();
                } else if (annotation instanceof RequestBody) {
                    body = args[i];
                }
            }
        }

        HttpServletRequest request = findRequest(args);
        if (request == null) {
            // 真实控制器（如 AdminController.updateConfig）只声明 @RequestHeader，不注入 HttpServletRequest，
            // 因此必须从当前线程的请求上下文取；否则审计会静默丢失（task-66 的生产事故）。
            request = currentRequest();
        }

        Long actorId = parseLong(request == null ? null : request.getHeader("X-User-Id"));
        if (actorId == null) {
            log.warn("admin audit skipped: no actor (X-User-Id header) action={}, method={}",
                    adminAudit.action(), signature.getName());
            return;
        }

        String actorName = null;
        User actor = userMapper.selectById(actorId);
        if (actor != null) {
            actorName = actor.getNickname() != null && !actor.getNickname().isBlank()
                    ? actor.getNickname() : actor.getUsername();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("method", signature.getName());
        detail.put("path", request == null ? "" : request.getRequestURI());
        String query = request == null ? null : request.getQueryString();
        if (query != null) {
            detail.put("query", truncate(query, MAX_QUERY_CHARS));
        }
        if (body != null) {
            detail.put("body", serializeSafely(body));
        }

        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            detailJson = "{\"error\":\"detail serialization failed\"}";
        }

        auditService.record(actorId, actorName, adminAudit.action(), adminAudit.targetType(),
                targetId, detailJson, clientIp(request));
    }

    /** 方法参数里显式声明的 HttpServletRequest（兼容老写法）；多数控制器没有，故还需上下文兜底。 */
    private static HttpServletRequest findRequest(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof HttpServletRequest httpServletRequest) {
                return httpServletRequest;
            }
        }
        return null;
    }

    /** 当前线程绑定的 request；非 Web 线程或测试环境返回 null。 */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }

    /** 请求体序列化；命中疑似敏感字段名时整体省略值（不写密码/令牌）。 */
    private String serializeSafely(Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            String lower = json.toLowerCase(Locale.ROOT);
            if (lower.contains("password") || lower.contains("secret") || lower.contains("token")) {
                return "<omitted> (sensitive)";
            }
            return truncate(json, MAX_BODY_CHARS);
        } catch (JsonProcessingException e) {
            return "<unserializable>";
        }
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return truncate((comma > 0 ? forwarded.substring(0, comma) : forwarded).trim(), MAX_IP_CHARS);
        }
        return truncate(request.getRemoteAddr(), MAX_IP_CHARS);
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
