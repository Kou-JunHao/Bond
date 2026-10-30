package com.bond.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限矩阵回归（task-156）：网关白名单 + 内部签名过滤器。
 *
 * <p>本类只覆盖「进入 Controller 之前」的两道门：</p>
 * <ol>
 *   <li>{@link PublicEndpoints}：哪些路径允许匿名（必须精确、不可被路径穿越绕过）；</li>
 *   <li>{@link InternalAuthFilter}：其余 {@code /api/**} 必须带网关 HMAC 签名，且签名错误/过期一律 401。</li>
 * </ol>
 *
 * <p>表里每一行都是历史上踩过的坑：裸 X-User-Id 伪造、{@code /dav} 白名单漏 401、预览令牌路径
 * 漏白名单导致 {@code <img>} 401、{@code /api/inviteX} 前缀绕过等。失败信息会打印「方法 + 路径 +
 * 期望 + 实际」，便于定位。</p>
 */
class AuthzMatrixTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final InternalAuthFilter filter = new InternalAuthFilter(SECRET);

    // ── 1. 白名单：必须匿名放行（进入过滤器链） ──

    static Stream<Arguments> publicEndpoints() {
        String shareToken = "A".repeat(43);
        return Stream.of(
                Arguments.of("GET", "/api/auth/captcha"),
                Arguments.of("GET", "/api/auth/captcha/check"),
                Arguments.of("GET", "/favicon.svg"),
                Arguments.of("POST", "/api/auth/login"),
                Arguments.of("POST", "/api/auth/register"),
                Arguments.of("POST", "/api/auth/refresh"),
                Arguments.of("POST", "/api/auth/email/code"),
                Arguments.of("POST", "/api/auth/password/reset"),
                Arguments.of("GET", "/api/invite/AbCdEf123"),
                Arguments.of("GET", "/api/transfer/avatar/12160299971645440"),
                Arguments.of("GET", "/api/files/12160299971645440/content"),
                Arguments.of("GET", "/api/files/12160299971645440/thumbnail"),
                Arguments.of("GET", "/api/files/12160299971645440/preview"),
                Arguments.of("GET", "/api/files/12160299971645440/office-pdf"),
                Arguments.of("GET", "/api/workspaces/7/files/12160299971645440/content"),
                Arguments.of("GET", "/api/workspaces/7/files/12160299971645440/thumbnail"),
                Arguments.of("GET", "/api/chat/files/meta"),
                Arguments.of("GET", "/api/chat/files/content"),
                Arguments.of("GET", "/api/chat/files/thumbnail"),
                Arguments.of("GET", "/api/chat/files/office-pdf"),
                Arguments.of("GET", "/api/users/12160299971645440/avatar"),
                Arguments.of("GET", "/s/" + shareToken),
                Arguments.of("GET", "/s/" + shareToken + "/download"),
                Arguments.of("POST", "/s/" + shareToken + "/verify")
        );
    }

    @ParameterizedTest(name = "[匿名] {0} {1} 应放行")
    @MethodSource("publicEndpoints")
    void publicEndpointsBypassSignature(String method, String path) throws Exception {
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertTrue(chained.get(), "白名单路径被 InternalAuthFilter 拦截：" + method + " " + path);
        assertEquals(200, response.getStatus(), method + " " + path);
    }

    // ── 2. 其余 /api/** 必须签名；缺失/错误/过期一律 401 且不进入下游 ──

    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(
                // 文件
                Arguments.of("GET", "/api/files"),
                Arguments.of("GET", "/api/files/quota"),
                Arguments.of("GET", "/api/files/search?q=probe"),
                Arguments.of("GET", "/api/files/12160299971645440/download"),
                Arguments.of("POST", "/api/files/upload"),
                Arguments.of("POST", "/api/files/folders"),
                Arguments.of("DELETE", "/api/files/12160299971645440"),
                // 工作区
                Arguments.of("GET", "/api/workspaces"),
                Arguments.of("POST", "/api/workspaces"),
                Arguments.of("GET", "/api/workspaces/7/members"),
                Arguments.of("PUT", "/api/workspaces/7/files/12/chunks/u1/0"),
                // 文档
                Arguments.of("GET", "/api/documents"),
                Arguments.of("POST", "/api/documents"),
                Arguments.of("PUT", "/api/documents/12"),
                Arguments.of("PATCH", "/api/documents/12"),
                Arguments.of("DELETE", "/api/documents/12"),
                Arguments.of("GET", "/api/documents/12/export?format=pdf"),
                // 聊天
                Arguments.of("GET", "/api/chat/conversations"),
                Arguments.of("POST", "/api/chat/conversations"),
                Arguments.of("POST", "/api/chat/messages/12/recall"),
                Arguments.of("GET", "/api/chat/unread"),
                Arguments.of("POST", "/api/chat/files/12/tokens"),
                // 分享（登录态管理面）
                Arguments.of("GET", "/api/shares"),
                Arguments.of("POST", "/api/shares"),
                Arguments.of("DELETE", "/api/shares/12"),
                // 回收站
                Arguments.of("GET", "/api/trash"),
                Arguments.of("POST", "/api/trash/restore"),
                // 权益 / 公告
                Arguments.of("GET", "/api/entitlements/me"),
                Arguments.of("GET", "/api/announcements"),
                Arguments.of("GET", "/api/announcements/unread-count"),
                // 软件分发 / 用户资料 / 头像上传
                Arguments.of("GET", "/api/software/packages"),
                Arguments.of("GET", "/api/software/releases/12/download"),
                Arguments.of("GET", "/api/auth/me"),
                Arguments.of("PUT", "/api/auth/profile"),
                Arguments.of("POST", "/api/auth/avatar"),
                Arguments.of("DELETE", "/api/auth/avatar"),
                Arguments.of("GET", "/api/users/12/profile"),
                // WebDAV 密码
                Arguments.of("GET", "/api/webdav/passwords"),
                Arguments.of("POST", "/api/webdav/passwords"),
                // 管理端
                Arguments.of("GET", "/api/admin/users"),
                Arguments.of("POST", "/api/admin/mail/test"),
                Arguments.of("PUT", "/api/admin/config/probe")
        );
    }

    @ParameterizedTest(name = "[匿名] {0} {1} 应 401")
    @MethodSource("protectedEndpoints")
    void protectedEndpointsRejectAnonymous(String method, String path) throws Exception {
        MockHttpServletResponse response = invoke(request(method, path), false);
        assertEquals(401, response.getStatus(), "越权放行：" + method + " " + path);
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), "响应信封不符合契约：" + method + " " + path + " -> " + body);
        assertTrue(body.contains("缺少内部签名凭证"), "缺失签名时 message 不可区分：" + body);
    }

    @Test
    void badSignatureRejectedWithDistinctMessage() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/files");
        long now = System.currentTimeMillis();
        request.addHeader(GatewaySignature.HEADER_USER_ID, "7");
        request.addHeader(GatewaySignature.HEADER_TIMESTAMP, String.valueOf(now));
        request.addHeader(GatewaySignature.HEADER_NONCE, UUID.randomUUID().toString());
        request.addHeader(GatewaySignature.HEADER_SIGNATURE,
                GatewaySignature.sign(SECRET, 7L, now, "other-nonce"));

        MockHttpServletResponse response = invoke(request, false);
        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("内部签名不匹配"), body);
        assertFalse(body.contains("缺少内部签名凭证"), body);
    }

    @Test
    void expiredSignatureRejected() throws Exception {
        long stale = System.currentTimeMillis() - GatewaySignature.MAX_CLOCK_SKEW_MS - 5_000L;
        MockHttpServletRequest request = request("GET", "/api/files");
        sign(request, 7L, stale, "nonce-expired");

        MockHttpServletResponse response = invoke(request, false);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("内部签名不匹配"));
    }

    @Test
    void validSignaturePassesAndExposesVerifiedUser() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/files");
        sign(request, 42L, System.currentTimeMillis(), "nonce-ok");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertTrue(chained.get(), "合法签名被误拦");
        assertEquals(42L, request.getAttribute(GatewaySignature.ATTR_VERIFIED_USER_ID));
    }

    @Test
    void spoofedUserIdWithoutSignatureNeverPasses() throws Exception {
        // 历史事故：下游直接信 X-User-Id。这里裸头必须 401。
        MockHttpServletRequest request = request("GET", "/api/files");
        request.addHeader(GatewaySignature.HEADER_USER_ID, "1");

        MockHttpServletResponse response = invoke(request, false);
        assertEquals(401, response.getStatus());
        assertNull(request.getAttribute(GatewaySignature.ATTR_VERIFIED_USER_ID));
    }

    @Test
    void malformedUserIdHeaderRejected() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/files");
        long now = System.currentTimeMillis();
        request.addHeader(GatewaySignature.HEADER_USER_ID, "admin");
        request.addHeader(GatewaySignature.HEADER_TIMESTAMP, String.valueOf(now));
        request.addHeader(GatewaySignature.HEADER_NONCE, "n1");
        request.addHeader(GatewaySignature.HEADER_SIGNATURE, GatewaySignature.sign(SECRET, 1L, now, "n1"));

        assertEquals(401, invoke(request, false).getStatus());
    }

    // ── 3. WebDAV 与静态资源/WS：由自身认证或网关直连负责，内部签名过滤器放行 ──

    static Stream<Arguments> bypassedPaths() {
        return Stream.of(
                Arguments.of("PROPFIND", "/dav"),
                Arguments.of("OPTIONS", "/dav/"),
                Arguments.of("PUT", "/dav/self/notes.txt"),
                Arguments.of("GET", "/actuator/health"),
                Arguments.of("GET", "/favicon.svg"),
                Arguments.of("GET", "/ws/chat")
        );
    }

    @ParameterizedTest(name = "[绕过] {0} {1}")
    @MethodSource("bypassedPaths")
    void nonApiAndWebDavPathsBypassInternalSignature(String method, String path) throws Exception {
        MockHttpServletResponse response = invoke(method, path);
        assertEquals(200, response.getStatus(), "被误拦：" + method + " " + path);
    }

    // ── 4. 白名单必须精确：错误方法 / 前缀绕过 / 穿越 / 形状不符 一律不放行 ──

    static Stream<Arguments> whitelistMatrix() {
        String shareToken = "B".repeat(43);
        return Stream.of(
                // 精确白名单
                Arguments.of("GET", "/api/auth/captcha", true),
                Arguments.of("GET", "/api/auth/captcha/check", true),
                Arguments.of("GET", "/favicon.svg", true),
                Arguments.of("POST", "/api/auth/login", true),
                Arguments.of("POST", "/api/auth/password/reset", true),
                // 方法必须匹配
                Arguments.of("GET", "/api/auth/login", false),
                Arguments.of("POST", "/api/auth/captcha", false),
                Arguments.of("PUT", "/api/files/12/content", false),
                Arguments.of("GET", "/s/" + shareToken + "/verify", false),
                // 前缀必须带子路径
                Arguments.of("GET", "/api/invite/", false),
                Arguments.of("GET", "/api/transfer/avatar/", false),
                Arguments.of("GET", "/api/inviteX/abc", false),
                // 形状不符
                Arguments.of("GET", "/api/files/12", false),
                Arguments.of("GET", "/api/files/abc/content", false),
                Arguments.of("GET", "/api/users/avatar", false),
                Arguments.of("GET", "/api/users/12/avatar/x", false),
                Arguments.of("GET", "/api/chat/files/12/content", false),
                Arguments.of("GET", "/s/short", false),
                Arguments.of("POST", "/s/" + shareToken + "/download", false),
                // 穿越 / 重复斜杠 / 编码斜杠
                Arguments.of("GET", "/api/files/12/../content", false),
                Arguments.of("GET", "/api/files//12/content", false),
                Arguments.of("GET", "/api/files/12/%2e%2e/content", false),
                Arguments.of("GET", "/api/files/12/%2Fcontent", false),
                Arguments.of("GET", "/api/../api/auth/captcha", false)
        );
    }

    @ParameterizedTest(name = "isPublic({0}, {1}) == {2}")
    @MethodSource("whitelistMatrix")
    void publicWhitelistIsExact(String method, String path, boolean expected) {
        assertEquals(expected, PublicEndpoints.isPublic(method, path),
                "白名单判定错误：" + method + " " + path);
    }

    // ── helpers ──

    private static MockHttpServletRequest request(String method, String path) {
        String uri = path;
        int q = path.indexOf('?');
        if (q >= 0) {
            uri = path.substring(0, q);
        }
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (q >= 0) {
            request.setQueryString(path.substring(q + 1));
        }
        return request;
    }

    private void sign(MockHttpServletRequest request, long userId, long timestamp, String nonce) {
        request.addHeader(GatewaySignature.HEADER_USER_ID, String.valueOf(userId));
        request.addHeader(GatewaySignature.HEADER_TIMESTAMP, String.valueOf(timestamp));
        request.addHeader(GatewaySignature.HEADER_NONCE, nonce);
        request.addHeader(GatewaySignature.HEADER_SIGNATURE,
                GatewaySignature.sign(SECRET, userId, timestamp, nonce));
    }

    private MockHttpServletResponse invoke(String method, String path) throws Exception {
        return invoke(request(method, path), true);
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request, boolean withValidSignature) throws Exception {
        if (withValidSignature) {
            long now = System.currentTimeMillis();
            sign(request, 7L, now, UUID.randomUUID().toString());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
        });
        return response;
    }
}
