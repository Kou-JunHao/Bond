package com.bond.gateway.filter;

import com.bond.common.security.GatewaySignature;
import com.bond.common.security.PublicEndpoints;
import com.bond.common.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限矩阵回归（task-156）：网关 JWT 门。
 *
 * <p>网关是唯一对公网暴露的入口，规则必须与下游 {@link com.bond.common.security.InternalAuthFilter}
 * 一致：白名单匿名放行、其余 {@code /api/**} 需要合法 JWT、{@code /dav/**} 交给 WebDAV 自己的
 * Basic 认证；通过后剥离客户端伪造的 {@code X-User-Id} 并用内部密钥重新签名。</p>
 */
class GatewayAuthzMatrixTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final AuthFilter filter = new AuthFilter(SECRET);

    // ── 1. 匿名访问受保护接口必须 401（含信封） ──

    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(
                Arguments.of("GET", "/api/files"),
                Arguments.of("GET", "/api/files/12/download"),
                Arguments.of("POST", "/api/files/upload"),
                Arguments.of("GET", "/api/workspaces"),
                Arguments.of("GET", "/api/documents"),
                Arguments.of("GET", "/api/chat/conversations"),
                Arguments.of("GET", "/api/shares"),
                Arguments.of("GET", "/api/trash"),
                Arguments.of("GET", "/api/entitlements/me"),
                Arguments.of("GET", "/api/software/packages"),
                Arguments.of("GET", "/api/software/releases/12/download"),
                Arguments.of("GET", "/api/users/12/profile"),
                Arguments.of("GET", "/api/auth/me"),
                Arguments.of("GET", "/api/admin/users"),
                Arguments.of("POST", "/api/admin/mail/test"),
                Arguments.of("GET", "/api/announcements"),
                Arguments.of("GET", "/api/webdav/passwords"),
                Arguments.of("DELETE", "/api/files/12")
        );
    }

    @ParameterizedTest(name = "[匿名] {0} {1} 应 401")
    @MethodSource("protectedEndpoints")
    void anonymousRejectedWithEnvelope(String method, String path) {
        Result r = invoke(exchange(method, path, null, null));
        assertFalse(r.passed(), "越权放行：" + method + " " + path);
        assertEquals(HttpStatus.UNAUTHORIZED, r.status(), method + " " + path);
        assertTrue(r.body().contains("\"code\":401"), "401 信封缺失：" + r.body());
        assertTrue(r.body().contains("缺少访问令牌"), "message 不可区分：" + r.body());
    }

    @Test
    void malformedAuthorizationRejected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(HttpMethod.GET, "http://localhost/api/files")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .build());
        Result r = invoke(exchange);
        assertEquals(HttpStatus.UNAUTHORIZED, r.status());
        assertTrue(r.body().contains("凭证格式错误"), r.body());
    }

    @Test
    void invalidTokenRejected() {
        Result r = invoke(exchange("GET", "/api/files", "abc.def.ghi", null));
        assertEquals(HttpStatus.UNAUTHORIZED, r.status());
        assertTrue(r.body().contains("登录凭证无效") || r.body().contains("凭证格式错误"), r.body());
    }

    @Test
    void expiredTokenRejectedWithWwwAuthenticate() {
        String expired = JwtUtils.generateToken(7L, "ccc", -1_000L);
        Result r = invoke(exchange("GET", "/api/files", expired, null));
        assertEquals(HttpStatus.UNAUTHORIZED, r.status());
        assertTrue(r.body().contains("登录已过期"), r.body());
        assertNotNull(r.wwwAuthenticate(), "过期必须带 WWW-Authenticate");
        assertTrue(r.wwwAuthenticate().contains("invalid_token"), r.wwwAuthenticate());
    }

    // ── 2. 白名单 / WebDAV 匿名放行 ──

    static Stream<Arguments> anonymousAllowed() {
        String shareToken = "C".repeat(43);
        return Stream.of(
                Arguments.of("GET", "/api/auth/captcha"),
                Arguments.of("GET", "/api/auth/captcha/check"),
                Arguments.of("POST", "/api/auth/login"),
                Arguments.of("POST", "/api/auth/register"),
                Arguments.of("POST", "/api/auth/refresh"),
                Arguments.of("POST", "/api/auth/email/code"),
                Arguments.of("POST", "/api/auth/password/reset"),
                Arguments.of("GET", "/api/invite/AbC123"),
                Arguments.of("GET", "/api/transfer/avatar/12"),
                Arguments.of("GET", "/api/files/12/content"),
                Arguments.of("GET", "/api/workspaces/7/files/12/thumbnail"),
                Arguments.of("GET", "/api/chat/files/content"),
                Arguments.of("GET", "/api/users/12/avatar"),
                Arguments.of("GET", "/s/" + shareToken),
                Arguments.of("GET", "/s/" + shareToken + "/download"),
                Arguments.of("POST", "/s/" + shareToken + "/verify"),
                Arguments.of("GET", "/favicon.svg"),
                Arguments.of("PROPFIND", "/dav"),
                Arguments.of("PUT", "/dav/self/a.txt")
        );
    }

    @ParameterizedTest(name = "[匿名放行] {0} {1}")
    @MethodSource("anonymousAllowed")
    void publicAndDavPassThrough(String method, String path) {
        Result r = invoke(exchange(method, path, null, null));
        assertTrue(r.passed(), "白名单被拦截：" + method + " " + path + " -> " + r.status());
    }

    // ── 3. 合法 token：放行并注入内部签名；客户端伪造头必须被剥离 ──

    @Test
    void validTokenInjectsIdentityAndStripsSpoofedHeaders() {
        String token = JwtUtils.generateAccessToken(7L, "ccc");
        Result r = invoke(exchange("GET", "/api/files", token, "999"));

        assertTrue(r.passed(), "合法 token 被拦截：" + r.status());
        ServerWebExchange forwarded = r.forwarded();
        assertNotNull(forwarded);
        assertEquals("7", forwarded.getRequest().getHeaders().getFirst(GatewaySignature.HEADER_USER_ID),
                "下游 X-User-Id 必须是 token 中的用户，不能是客户端伪造值");
        assertNotNull(forwarded.getRequest().getHeaders().getFirst(GatewaySignature.HEADER_SIGNATURE),
                "下游必须带网关签名");
        assertNotNull(forwarded.getRequest().getHeaders().getFirst(GatewaySignature.HEADER_TIMESTAMP));
        assertNotNull(forwarded.getRequest().getHeaders().getFirst(GatewaySignature.HEADER_NONCE));
    }

    @Test
    void userWithoutAdminRightsStillPassesGateway() {
        // 管理权限在下游 AdminInterceptor 判定；网关只负责认证，不能因非管理员就 401。
        String token = JwtUtils.generateAccessToken(7L, "normal-user");
        Result r = invoke(exchange("GET", "/api/admin/users", token, null));
        assertTrue(r.passed(), "网关不应替下游做管理员鉴权：" + r.status());
    }

    @Test
    void spoofedHeadersWithoutTokenAreRejected() {
        Result r = invoke(exchange("GET", "/api/files", null, "7"));
        assertEquals(HttpStatus.UNAUTHORIZED, r.status());
    }

    // ── helpers ──

    private MockServerWebExchange exchange(String method, String path, String bearerOrRaw, String spoofedUserId) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest
                .method(HttpMethod.valueOf(method), "http://localhost" + path);
        if (bearerOrRaw != null) {
            String value = bearerOrRaw.startsWith("Bearer ") || bearerOrRaw.contains(".")
                    ? (bearerOrRaw.contains(" ") ? bearerOrRaw : "Bearer " + bearerOrRaw)
                    : "Bearer " + bearerOrRaw;
            builder.header(HttpHeaders.AUTHORIZATION, value);
        }
        if (spoofedUserId != null) {
            builder.header(GatewaySignature.HEADER_USER_ID, spoofedUserId);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private Result invoke(MockServerWebExchange exchange) {
        AtomicBoolean passed = new AtomicBoolean(false);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, e -> {
            passed.set(true);
            forwarded.set(e);
            return Mono.empty();
        }).block();

        HttpStatus status = exchange.getResponse().getStatusCode() == null
                ? HttpStatus.OK
                : HttpStatus.valueOf(exchange.getResponse().getStatusCode().value());
        String body;
        try {
            body = exchange.getResponse().getBodyAsString().blockOptional().orElse("");
        } catch (Exception e) {
            body = "";
        }
        String www = exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        return new Result(passed.get(), status, body, www, forwarded.get());
    }

    private record Result(boolean passed, HttpStatus status, String body, String wwwAuthenticate,
                          ServerWebExchange forwarded) {
    }
}
