package com.bond.transfer.webdav;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bond.common.entity.User;
import com.bond.common.entity.WebDavPassword;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WebDavPasswordMapper;
import com.bond.transfer.service.ConfigService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class WebDavAuthServiceTest {

    @Mock
    private ConfigService configService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private WebDavPasswordMapper webDavPasswordMapper;

    private WebDavAuthService auth;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        auth = new WebDavAuthService(configService, userMapper, webDavPasswordMapper);
    }

    private static MockHttpServletRequest request(String ip, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dav/");
        request.setRemoteAddr(ip);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private static String basic(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void disabledByDefault() {
        when(configService.getBoolean("webdav.enabled", false)).thenReturn(false);

        WebDavException error = assertThrows(WebDavException.class, () -> auth.ensureAvailable(request("1.1.1.1", null)));
        assertEquals(404, error.getStatus());
    }

    @Test
    void rejectsPlainHttpBehindProxy() {
        when(configService.getBoolean("webdav.enabled", false)).thenReturn(true);
        MockHttpServletRequest request = request("1.1.1.1", null);
        request.addHeader("X-Forwarded-Proto", "http");

        WebDavException error = assertThrows(WebDavException.class, () -> auth.ensureAvailable(request));
        assertEquals(403, error.getStatus());
    }

    @Test
    void acceptsHttpsAnywhereInForwardedProtoChain() {
        when(configService.getBoolean("webdav.enabled", false)).thenReturn(true);
        MockHttpServletRequest request = request("1.1.1.1", null);
        // nginx 终止 TLS 设 https，Spring Cloud Gateway 可能追加自己的 http
        request.addHeader("X-Forwarded-Proto", "https,http");

        assertDoesNotThrow(() -> auth.ensureAvailable(request));
    }

    @Test
    void missingAuthorizationIs401WithChallenge() {
        WebDavException error = assertThrows(WebDavException.class,
                () -> auth.authenticate(request("1.1.1.1", null)));

        assertEquals(401, error.getStatus());
        assertNotNull(error.getHeaders().get("WWW-Authenticate"));
    }

    @Test
    void wrongPasswordIs401() {
        when(userMapper.selectOne(any())).thenReturn(user("alice", encoder.encode("correct")));

        WebDavException error = assertThrows(WebDavException.class,
                () -> auth.authenticate(request("1.1.1.1", basic("alice", "wrong"))));
        assertEquals(401, error.getStatus());
    }

    @Test
    void correctCredentialsReturnUserIdAndAreCached() {
        when(userMapper.selectOne(any())).thenReturn(user("alice", encoder.encode("secret")));

        Long first = auth.authenticate(request("1.1.1.1", basic("Alice", "secret")));
        Long second = auth.authenticate(request("1.1.1.1", basic("alice", "secret")));

        assertEquals(7L, first);
        assertEquals(7L, second);
        verify(userMapper, times(1)).selectOne(any());
    }

    @Test
    void tenFailuresThenRateLimited() {
        when(userMapper.selectOne(any())).thenReturn(null);

        for (int i = 0; i < 10; i++) {
            WebDavException error = assertThrows(WebDavException.class,
                    () -> auth.authenticate(request("9.9.9.9", basic("alice", "wrong" + System.nanoTime()))));
            assertEquals(401, error.getStatus());
        }

        WebDavException limited = assertThrows(WebDavException.class,
                () -> auth.authenticate(request("9.9.9.9", basic("alice", "whatever"))));
        assertEquals(429, limited.getStatus());
        assertNotNull(limited.getHeaders().get("Retry-After"));
    }

    @Test
    void acceptsDomainPrefixedUsernameFromWindowsExplorer() {
        when(userMapper.selectOne(any())).thenReturn(user("alice", encoder.encode("secret")));

        Long userId = auth.authenticate(request("1.1.1.1", basic("CORP\\alice", "secret")));

        assertEquals(7L, userId);
    }

    @Test
    void malformedBasicHeaderIs401() {
        WebDavException error = assertThrows(WebDavException.class,
                () -> auth.authenticate(request("1.1.1.1", "Basic " + Base64.getEncoder()
                        .encodeToString("no-colon".getBytes(StandardCharsets.UTF_8)))));
        assertEquals(401, error.getStatus());

        WebDavException bearer = assertThrows(WebDavException.class,
                () -> auth.authenticate(request("1.1.1.1", "Bearer token")));
        assertEquals(401, bearer.getStatus());
    }

    @Test
    void appPasswordMatchesBeforeLoginPassword() {
        when(userMapper.selectOne(any())).thenReturn(user("alice", encoder.encode("login-pass")));
        when(webDavPasswordMapper.selectList(any()))
                .thenReturn(List.of(webdavPassword(11L, encoder.encode("app-secret"))));

        Long userId = auth.authenticate(request("1.1.1.1", basic("alice", "app-secret")));

        assertEquals(7L, userId);
        // 专用密码命中时刷新 last_used_at
        verify(webDavPasswordMapper, times(1)).updateById(any(WebDavPassword.class));
    }

    @Test
    void loginPasswordStillWorksAsFallbackDuringMigration() {
        when(userMapper.selectOne(any())).thenReturn(user("alice", encoder.encode("login-pass")));
        // 该用户有专用密码，但提交的是登录密码 → 回退到登录密码校验
        when(webDavPasswordMapper.selectList(any()))
                .thenReturn(List.of(webdavPassword(11L, encoder.encode("app-secret"))));

        Long userId = auth.authenticate(request("1.1.1.1", basic("alice", "login-pass")));

        assertEquals(7L, userId);
        verify(webDavPasswordMapper, never()).updateById(any(WebDavPassword.class));
    }

    @Test
    void invalidateUserEvictsCacheImmediately() {
        when(userMapper.selectOne(any())).thenReturn(user("alice", encoder.encode("login-pass")));
        when(webDavPasswordMapper.selectList(any()))
                .thenReturn(List.of(webdavPassword(11L, encoder.encode("app-secret"))))
                .thenReturn(List.of());

        assertEquals(7L, auth.authenticate(request("1.1.1.1", basic("alice", "app-secret"))));
        // 模拟 revoke：缓存必须立即失效，而不是等 10 分钟 TTL
        auth.invalidateUser(7L);

        WebDavException error = assertThrows(WebDavException.class,
                () -> auth.authenticate(request("1.1.1.1", basic("alice", "app-secret"))));
        assertEquals(401, error.getStatus());
        verify(userMapper, times(2)).selectOne(any());
    }

    private static WebDavPassword webdavPassword(Long id, String passwordHash) {
        WebDavPassword row = new WebDavPassword();
        row.setId(id);
        row.setUserId(7L);
        row.setName("laptop");
        row.setPasswordHash(passwordHash);
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }

    private static User user(String username, String passwordHash) {
        User user = new User();
        user.setId(7L);
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        return user;
    }
}
