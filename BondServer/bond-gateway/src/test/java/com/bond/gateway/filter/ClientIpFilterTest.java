package com.bond.gateway.filter;

import com.bond.common.security.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 网关真实 IP 覆写（task-68）：直连来源伪造的 X-Real-IP/X-Forwarded-For 必须被丢弃，
 * 下游只能看到网关计算出的单一 IP。
 */
class ClientIpFilterTest {

    private static final String XFF = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";

    private final ClientIpFilter filter = new ClientIpFilter(ClientIpResolver.DEFAULT_TRUSTED_PROXIES);

    @Test
    @DisplayName("信任代理（nginx 172.18.x）：采用 nginx 写入的 X-Real-IP，下游只看到单一值")
    void trustedProxyUsesRealIp() {
        assertEquals("198.51.100.9",
                filter.resolveClientIp("172.18.0.9", "9.9.9.9", "198.51.100.9"));
    }

    @Test
    @DisplayName("直连客户端伪造代理头：忽略伪造头，用直连地址")
    void directClientSpoofDropped() {
        assertEquals("203.0.113.7",
                filter.resolveClientIp("203.0.113.7", "8.8.8.8", "8.8.8.4"));
    }

    @Test
    @DisplayName("过滤器：请求头被改写为单一 clientIp（同时覆盖 XFF 与 X-Real-IP）")
    void filterRewritesHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/auth/login")
                .remoteAddress(new InetSocketAddress("172.18.0.9", 52314))
                .header(XFF, "8.8.8.8, 10.0.0.1")
                .header(REAL_IP, "198.51.100.9")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertEquals("198.51.100.9", headers.getFirst(REAL_IP));
        assertEquals("198.51.100.9", headers.getFirst(XFF));
        assertEquals(1, headers.get(REAL_IP).size());
        assertEquals(1, headers.get(XFF).size());
    }

    @Test
    @DisplayName("过滤器：直连客户端伪造头被替换为其真实地址")
    void filterDropsSpoofedHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/auth/login")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 40000))
                .header(XFF, "8.8.8.8")
                .header(REAL_IP, "8.8.8.8")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertEquals("203.0.113.7", headers.getFirst(REAL_IP));
        assertEquals("203.0.113.7", headers.getFirst(XFF));
    }

    @Test
    @DisplayName("过滤器顺序早于 AuthFilter（-100）")
    void runsBeforeAuthFilter() {
        assertEquals(-200, filter.getOrder());
    }

    @Test
    @DisplayName("无 remote address 时不写头")
    void emptyRemoteLeavesNoHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/x").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertNull(headers.getFirst(REAL_IP));
        assertNull(headers.getFirst(XFF));
    }

    @Test
    @DisplayName("task-146：可信代理 + 客户端伪造最左 XFF 时，取最右非可信跳而非伪造值")
    void forgedLeftmostXffIgnored() {
        // 客户端自带 "8.8.8.8"，nginx 在右侧追加真实来源 203.0.113.7；无 X-Real-IP 时取右侧
        assertEquals("203.0.113.7",
                filter.resolveClientIp("172.18.0.9", "8.8.8.8, 203.0.113.7", null));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/auth/login")
                .remoteAddress(new InetSocketAddress("172.18.0.9", 40001))
                .header(XFF, "8.8.8.8, 203.0.113.7")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertEquals("203.0.113.7", headers.getFirst(REAL_IP));
        assertEquals("203.0.113.7", headers.getFirst(XFF));
    }
}
