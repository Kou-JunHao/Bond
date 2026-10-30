package com.bond.transfer.audit;

import com.bond.common.entity.User;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理操作审计切面单测（任务单 #19）。
 */
@ExtendWith(MockitoExtension.class)
class AdminAuditAspectTest {

    private static final Long ACTOR_ID = 1L;

    @Mock
    private AuditService auditService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    private AdminAuditAspect aspect;

    private AdminAudit annotation;

    /** 模拟被切的管理方法：真实实现（含 HttpServletRequest 与 @RequestBody 两个参数）。 */
    public static class Fixture {
        @AdminAudit(action = "user.delete", targetType = "user")
        public Object deleteUser(HttpServletRequest request, @RequestBody Map<String, Object> body) {
            return "ok";
        }

        /** 模拟真实 AdminController 写法：不注入 HttpServletRequest，只有 @RequestBody。 */
        @AdminAudit(action = "config.update", targetType = "config")
        public Object updateConfig(@RequestBody Map<String, Object> body) {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        aspect = new AdminAuditAspect(auditService, userMapper, new ObjectMapper());
        Method method = Fixture.class.getMethod("deleteUser", HttpServletRequest.class, Map.class);
        annotation = method.getAnnotation(AdminAudit.class);
        // 部分用例在 proceed 阶段就抛异常、不会进入审计记录，故用 lenient 避免严格桩校验误伤。
        org.mockito.Mockito.lenient().when(joinPoint.getSignature()).thenReturn(signature);
        org.mockito.Mockito.lenient().when(signature.getName()).thenReturn("deleteUser");
        org.mockito.Mockito.lenient().when(joinPoint.getArgs()).thenReturn(new Object[]{});
        org.mockito.Mockito.lenient().when(signature.getMethod()).thenReturn(method);
    }

    private static User user(String nickname, String username) {
        User user = new User();
        user.setId(ACTOR_ID);
        user.setNickname(nickname);
        user.setUsername(username);
        return user;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/admin/users/9");
        request.addHeader("X-User-Id", String.valueOf(ACTOR_ID));
        request.addHeader("X-Forwarded-For", "10.0.0.1, 172.16.0.1");
        request.setQueryString("force=true");
        return request;
    }

    @Test
    void recordsSuccessfulAdminOperation() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{request(), Map.of("reason", "spam")});
        when(userMapper.selectById(ACTOR_ID)).thenReturn(user("管理员", "admin"));

        Object result = aspect.around(joinPoint, annotation);

        assertEquals("ok", result);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(ACTOR_ID), eq("管理员"), eq("user.delete"), eq("user"),
                isNull(), detail.capture(), eq("10.0.0.1"));
        assertTrue(detail.getValue().contains("\"method\":\"deleteUser\""));
        assertTrue(detail.getValue().contains("/api/admin/users/9"));
        assertTrue(detail.getValue().contains("force=true"));
        assertTrue(detail.getValue().contains("spam"));
    }

    @Test
    void skipsWhenNoActorHeader() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/admin/users/9");
        when(joinPoint.getArgs()).thenReturn(new Object[]{request, Map.of("a", 1)});

        aspect.around(joinPoint, annotation);

        verifyNoInteractions(auditService);
    }

    @Test
    void doesNotRecordWhenOperationThrows() throws Throwable {
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> aspect.around(joinPoint, annotation));

        verifyNoInteractions(auditService);
    }

    @Test
    void masksSensitiveBodyContent() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{request(), Map.of("password", "s3cret")});
        when(userMapper.selectById(anyLong())).thenReturn(user("管理员", "admin"));

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(ACTOR_ID), eq("管理员"), eq("user.delete"), eq("user"),
                isNull(), detail.capture(), eq("10.0.0.1"));
        assertTrue(detail.getValue().contains("<omitted>"));
    }

    @Test
    void auditFailureDoesNotBreakOperation() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{request(), Map.of("reason", "x")});
        when(userMapper.selectById(anyLong())).thenReturn(user("管理员", "admin"));
        doThrow(new RuntimeException("audit db down"))
                .when(auditService).record(anyLong(), any(), any(), any(), any(), any(), any());

        assertEquals("ok", aspect.around(joinPoint, annotation));
    }

    @Test
    void fallsBackToUsernameWhenNicknameBlank() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{request(), Map.of("reason", "x")});
        when(userMapper.selectById(ACTOR_ID)).thenReturn(user("  ", "ccc"));

        aspect.around(joinPoint, annotation);

        verify(auditService).record(eq(ACTOR_ID), eq("ccc"), eq("user.delete"), eq("user"),
                isNull(), any(), eq("10.0.0.1"));
    }

    @Test
    void recordsActorFromRequestContextWhenNotInArguments() throws Throwable {
        Method method = Fixture.class.getMethod("updateConfig", Map.class);
        AdminAudit configAudit = method.getAnnotation(AdminAudit.class);
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{Map.of("key", "email.provider")});
        when(signature.getName()).thenReturn("updateConfig");
        when(signature.getMethod()).thenReturn(method);
        when(userMapper.selectById(ACTOR_ID)).thenReturn(user("管理员", "admin"));

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request()));
        try {
            assertEquals("ok", aspect.around(joinPoint, configAudit));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(ACTOR_ID), eq("管理员"), eq("config.update"), eq("config"),
                isNull(), detail.capture(), eq("10.0.0.1"));
        assertTrue(detail.getValue().contains("\"method\":\"updateConfig\""));
        assertTrue(detail.getValue().contains("email.provider"));
    }

    @Test
    void skipsAndWarnsWhenActorHeaderMissingEvenWithRequestContext() throws Throwable {
        Method method = Fixture.class.getMethod("updateConfig", Map.class);
        AdminAudit configAudit = method.getAnnotation(AdminAudit.class);
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{Map.of("k", "v")});
        when(signature.getName()).thenReturn("updateConfig");
        when(signature.getMethod()).thenReturn(method);

        MockHttpServletRequest anonymous = new MockHttpServletRequest("PUT", "/api/admin/config/email.provider");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(anonymous));
        try {
            // 缺 X-User-Id 时不落库、不抛异常（请求结果不受影响）。
            assertEquals("ok", aspect.around(joinPoint, configAudit));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        verifyNoInteractions(auditService);
    }
}
