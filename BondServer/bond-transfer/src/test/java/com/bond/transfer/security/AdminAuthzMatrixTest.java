package com.bond.transfer.security;

import com.bond.common.entity.User;
import com.bond.transfer.interceptor.AdminInterceptor;
import com.bond.transfer.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限矩阵回归（task-156）：管理端路由门（{@link AdminInterceptor}）。
 *
 * <p>覆盖历史上踩过的三类问题：</p>
 * <ol>
 *   <li>缺失/畸形 {@code X-User-Id} 不能变成 500（旧实现直接 {@code Long.parseLong} 抛异常）；</li>
 *   <li>普通用户 token（=我们登录了，但不是管理员）必须 403，而不是 401——客户端要能区分
 *       「没登录」和「没权限」；</li>
 *   <li>用户不存在/已删除也不能当管理员放行。</li>
 * </ol>
 */
class AdminAuthzMatrixTest {

    private AdminInterceptor interceptor;

    private static User user(long id, boolean admin) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setIsAdmin(admin);
        return user;
    }

    @BeforeEach
    void setUp() {
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.selectById(99L)).thenReturn(user(99L, true));
        when(userMapper.selectById(7L)).thenReturn(user(7L, false));
        when(userMapper.selectById(51L)).thenReturn(null);
        interceptor = new AdminInterceptor(userMapper);
    }

    static Stream<Arguments> headerMatrix() {
        return Stream.of(
                Arguments.of(null, 401),
                Arguments.of("  ", 401),
                Arguments.of("abc", 401),
                Arguments.of("7.5", 401),
                Arguments.of("0", 401),
                Arguments.of("-3", 401),
                Arguments.of("7", 403),
                Arguments.of("51", 403),
                Arguments.of("99", 200)
        );
    }

    @ParameterizedTest(name = "X-User-Id={0} → 期望 {1}")
    @MethodSource("headerMatrix")
    void adminRouteGate(String userIdHeader, int expected) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        if (userIdHeader != null) {
            request.addHeader("X-User-Id", userIdHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(request, response, new Object());

        if (expected == 200) {
            assertTrue(passed, "管理员被误拦：" + userIdHeader);
            return;
        }
        assertFalse(passed, "越权放行：" + userIdHeader);
        assertEquals(expected, response.getStatus(), "X-User-Id=" + userIdHeader);
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":" + expected), "响应不是统一信封：" + body);
        if (expected == 403) {
            assertTrue(body.contains("需要管理员权限"), "403 message 不可读：" + body);
        }
    }

    @Test
    void nonAdminIsForbiddenNotUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.addHeader("X-User-Id", "7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void deletedUserIsForbidden() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/mail/test");
        request.addHeader("X-User-Id", "51");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus(), "查不到用户时不能放行");
    }
}
