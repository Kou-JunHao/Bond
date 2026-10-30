package com.bond.transfer.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bond.common.entity.User;
import com.bond.transfer.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 管理端 401/403 信封（task-126）：message 可区分、body 统一为 JSON。
 */
class AdminInterceptorTest {

    private UserMapper userMapper;
    private AdminInterceptor interceptor;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        interceptor = new AdminInterceptor(userMapper);
    }

    private static MockHttpServletRequest request(String userIdHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        if (userIdHeader != null) {
            request.addHeader("X-User-Id", userIdHeader);
        }
        return request;
    }

    private boolean run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        return interceptor.preHandle(request, response, new Object());
    }

    @Test
    void missingHeaderIs401WithEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(request(null), response));

        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains("缺少访问令牌"), body);
        assertTrue(body.contains("\"data\":null"), body);
    }

    @Test
    void malformedHeaderIs401WithFormatMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(request("abc"), response));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("访问令牌格式非法"));
    }

    @Test
    void negativeIdIs401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(request("-1"), response));

        assertEquals(401, response.getStatus());
    }

    @Test
    void nonAdminIs403WithDistinctMessage() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setIsAdmin(false);
        when(userMapper.selectById(7L)).thenReturn(user);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(request("7"), response));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("需要管理员权限"));
    }

    @Test
    void unknownUserIs403() throws Exception {
        lenient().when(userMapper.selectById(99L)).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(request("99"), response));

        assertEquals(403, response.getStatus());
    }

    @Test
    void adminPasses() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setIsAdmin(true);
        when(userMapper.selectById(1L)).thenReturn(user);

        assertTrue(run(request("1"), new MockHttpServletResponse()));
    }
}
