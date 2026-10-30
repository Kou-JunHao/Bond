package com.bond.transfer.chat;

import com.bond.common.utils.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS 握手鉴权单测：验证 token 缺失/非法时返回 401 并拒绝升级；合法时写入 userId 属性。
 * <p>这是"握手失败行为"的契约测试——生产行为为 HTTP 401（不会先 101 再 close）。</p>
 */
class ChatWsHandshakeTest {

    private final ChatWsHandler.ChatWsHandshakeInterceptor interceptor = new ChatWsHandler.ChatWsHandshakeInterceptor();

    @Test
    @DisplayName("握手：缺 token → 401 + 拒绝")
    void missingTokenRejectedWith401() throws Exception {
        MockResponse response = new MockResponse();
        boolean accepted = interceptor.beforeHandshake(request("/ws/chat"), response, null, new HashMap<>());

        assertFalse(accepted);
        assertEquals(HttpStatus.UNAUTHORIZED, response.status());
    }

    @Test
    @DisplayName("握手：非法/过期 token → 401 + 拒绝")
    void invalidTokenRejectedWith401() throws Exception {
        MockResponse response = new MockResponse();
        boolean accepted = interceptor.beforeHandshake(request("/ws/chat?token=not-a-jwt"), response, null, new HashMap<>());

        assertFalse(accepted);
        assertEquals(HttpStatus.UNAUTHORIZED, response.status());
    }

    @Test
    @DisplayName("握手：合法 token → 放行并写入 chatUserId")
    void validTokenAccepted() throws Exception {
        String token = JwtUtils.generateAccessToken(1077L, "u");
        MockResponse response = new MockResponse();
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("/ws/chat?token=" + token), response, null, attributes);

        assertTrue(accepted);
        assertEquals(1077L, attributes.get(ChatWsHandler.ChatWsHandshakeInterceptor.ATTR_USER_ID));
        assertNull(response.status());
    }

    @Test
    @DisplayName("query 解析：token 在任意位置 / 缺失")
    void queryParsing() {
        assertEquals("abc", ChatWsHandler.ChatWsHandshakeInterceptor.queryParam("token=abc", "token"));
        assertEquals("abc", ChatWsHandler.ChatWsHandshakeInterceptor.queryParam("a=1&token=abc&b=2", "token"));
        assertNull(ChatWsHandler.ChatWsHandshakeInterceptor.queryParam("a=1", "token"));
        assertNull(ChatWsHandler.ChatWsHandshakeInterceptor.queryParam(null, "token"));
    }

    private static ServerHttpRequest request(String pathAndQuery) {
        return new ServerHttpRequest() {
            private final URI uri = URI.create(pathAndQuery);

            @Override public HttpMethod getMethod() { return HttpMethod.GET; }
            @Override public URI getURI() { return uri; }
            @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
            @Override public java.io.InputStream getBody() { throw new UnsupportedOperationException(); }
            @Override public java.util.Map<String, Object> getAttributes() { return new HashMap<>(); }
            @Override public Principal getPrincipal() { return null; }
            @Override public InetSocketAddress getLocalAddress() { return null; }
            @Override public InetSocketAddress getRemoteAddress() { return null; }
            @Override public ServerHttpAsyncRequestControl getAsyncRequestControl(ServerHttpResponse response) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** 只关心 status code 的响应桩。 */
    private static class MockResponse implements ServerHttpResponse {
        private final HttpHeaders headers = new HttpHeaders();
        private HttpStatusCode status;

        @Override public void setStatusCode(HttpStatusCode status) { this.status = status; }
        HttpStatusCode status() { return status; }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public OutputStream getBody() {
            return OutputStream.nullOutputStream();
        }
        @Override public void flush() { /* no-op */ }
        @Override public void close() { /* no-op */ }
    }
}
