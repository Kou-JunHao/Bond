package com.bond.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * InternalAuthFilter 的路径豁免规则回归测试（task-44）。
 *
 * <p>WebDAV 的入口 {@code /dav}、{@code /dav/**} 在网关上整体跳过 JWT 与内部签名注入，
 * 由 {@code WebDavAuthService} 用 HTTP Basic 自行鉴权；下游过滤器必须同样放行，
 * 否则 PROPFIND/PUT 等请求会被 401 拦掉。</p>
 */
class InternalAuthFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final InternalAuthFilter filter = new InternalAuthFilter(SECRET);

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void webDavPathsBypassGatewaySignature() {
        assertTrue(filter.shouldNotFilter(request("PROPFIND", "/dav")));
        assertTrue(filter.shouldNotFilter(request("PROPFIND", "/dav/")));
        assertTrue(filter.shouldNotFilter(request("PROPFIND", "/dav/workspaces/7/a%20b.txt")));
        assertTrue(filter.shouldNotFilter(request("PUT", "/dav/self/notes.txt")));
        assertTrue(filter.shouldNotFilter(request("MKCOL", "/dav/self/dir/")));
        assertTrue(filter.shouldNotFilter(request("LOCK", "/dav/self/notes.txt")));
        assertTrue(filter.shouldNotFilter(request("OPTIONS", "/dav/")));
    }

    @Test
    void apiPathsStillRequireSignature() {
        assertFalse(filter.shouldNotFilter(request("GET", "/api/files/1/download")));
        assertFalse(filter.shouldNotFilter(request("GET", "/api/entitlements/me")));
        assertFalse(filter.shouldNotFilter(request("PROPFIND", "/api/files/1")));
        assertFalse(filter.shouldNotFilter(request("PUT", "/api/files/upload")));
    }

    @Test
    void missingSignatureHeadersRejectedWithEnvelope() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/files/1/download");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response,
                (req, res) -> chained.set(true));

        assertFalse(chained.get());
        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains("缺少内部签名凭证"), body);
        assertTrue(body.contains("\"data\":null"), body);
    }

    @Test
    void invalidSignatureRejectedWithDistinctMessage() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/files/1/download");
        request.addHeader(GatewaySignature.HEADER_USER_ID, "7");
        request.addHeader(GatewaySignature.HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
        request.addHeader(GatewaySignature.HEADER_NONCE, "nonce-1");
        request.addHeader(GatewaySignature.HEADER_SIGNATURE, "deadbeef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertFalse(chained.get());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("内部签名不匹配"),
                response.getContentAsString());
    }

    @Test
    void validSignaturePasses() throws Exception {
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-2";
        String signature = GatewaySignature.sign(SECRET, 7L, timestamp, nonce);
        MockHttpServletRequest request = request("GET", "/api/files/1/download");
        request.addHeader(GatewaySignature.HEADER_USER_ID, "7");
        request.addHeader(GatewaySignature.HEADER_TIMESTAMP, String.valueOf(timestamp));
        request.addHeader(GatewaySignature.HEADER_NONCE, nonce);
        request.addHeader(GatewaySignature.HEADER_SIGNATURE, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertTrue(chained.get());
        assertEquals(200, response.getStatus());
    }
}
